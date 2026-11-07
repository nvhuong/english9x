package com.english9x.identity;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.english9x.identity.api.EmailNotVerifiedException;
import com.english9x.identity.api.UserDirectory;
import com.english9x.org.api.CurrentContextHolder;
import com.english9x.support.SmtpSink;
import com.english9x.support.TestAuth;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

class RegistrationIT extends IdentityIT {

    @Autowired
    UserDirectory directory;

    @Test
    void registrationSendsAVietnameseVerificationMailWithALinkOnTheConfiguredBaseUrl() {
        String email = uniqueEmail();
        Res r = register(email, PASSWORD);
        assertEquals(201, r.status(), r.raw());
        assertEquals("pending_verification", r.body().get("status").asText());

        SmtpSink.Mail mail = smtp().await(email);
        assertTrue(mail.subject().contains("Xác thực"), mail.subject());
        assertTrue(mail.link().startsWith(TestAuth.BASE_URL + "/verify-email?token="), mail.link());
        assertFalse(mail.body().contains(PASSWORD), "the mail never contains the password");

        Map<String, Object> row = su.queryForMap("select password_hash, email_verified_at, consent_at, locale from users where email = ?", email);
        assertTrue(((String) row.get("password_hash")).startsWith("$2"), "bcrypt");
        assertFalse(row.get("password_hash").toString().contains(PASSWORD));
        assertNull(row.get("email_verified_at"));
        assertNotNull(row.get("consent_at"), "the time of consent is stored (Decree 13/2023)");
        assertEquals("vi", row.get("locale"));
        assertNotNull(su.queryForObject("select token_hash from email_verifications where user_id = ?", byte[].class, userId(email)));
    }

    @Test
    void englishLocaleGetsAnEnglishMail() {
        String email = uniqueEmail();
        Res r = post("/api/auth/register", Map.of("email", email, "password", PASSWORD, "displayName", "Ann", "consent", true, "locale", "en"));
        assertEquals(201, r.status(), r.raw());
        assertTrue(smtp().await(email).subject().startsWith("Verify"), "english subject");
    }

    @Test
    void linksNeverFollowTheRequestHost() {
        String email = uniqueEmail();
        Res r = post("/api/auth/register", Map.of("email", email, "password", PASSWORD, "displayName", "Ann", "consent", true),
                Map.of("X-Forwarded-Host", "evil.example", "X-Forwarded-Proto", "http", "Forwarded", "host=evil.example"));
        assertEquals(201, r.status());
        assertTrue(smtp().await(email).link().startsWith(TestAuth.BASE_URL + "/"), smtp().await(email).link());
    }

    @Test
    void registrationWithoutConsentIsRejectedAndCreatesNothing() {
        String email = uniqueEmail();
        for (Object consent : new Object[] {false, null}) {
            Map<String, Object> body = new java.util.HashMap<>(Map.of("email", email, "password", PASSWORD, "displayName", "Ann"));
            if (consent != null) {
                body.put("consent", consent);
            }
            Res r = post("/api/auth/register", body);
            assertEquals(400, r.status(), r.raw());
            assertEquals("urn:e9x:problem:validation-failed", r.problemType());
            assertTrue(r.raw().contains("\"field\":\"consent\""), r.raw());
        }
        assertEquals(0, su.queryForObject("select count(*) from users where email = ?", Integer.class, email));
        assertEquals(0, smtp().countAfterSettling(email));
    }

    @Test
    void passwordPolicyShortAndOverBcryptLimit() {
        Res tooShort = register(uniqueEmail(), "short1!");
        assertEquals(400, tooShort.status());
        assertTrue(tooShort.raw().contains("\"field\":\"password\""), tooShort.raw());

        // 37 x "é" = 74 bytes in UTF-8 (only 37 characters): bcrypt would silently ignore bytes past 72
        Res tooManyBytes = register(uniqueEmail(), "é".repeat(37));
        assertEquals(400, tooManyBytes.status(), tooManyBytes.raw());
        assertTrue(tooManyBytes.raw().contains("\"field\":\"password\"") && tooManyBytes.raw().contains("Utf8MaxBytes"), tooManyBytes.raw());

        Res exactly72 = register(uniqueEmail(), "é".repeat(36));
        assertEquals(201, exactly72.status(), exactly72.raw());
        // and an over-long password at login is simply wrong, not an error that tells anything
        assertEquals(401, login(uniqueEmail(), "x".repeat(100)).status());
    }

    @Test
    void invalidEmailAndMissingFieldsListFieldErrors() {
        Res r = post("/api/auth/register", Map.of("email", "not-an-email", "password", PASSWORD, "displayName", " ", "consent", true));
        assertEquals(400, r.status());
        assertTrue(r.raw().contains("\"field\":\"email\"") && r.raw().contains("\"field\":\"displayName\""), r.raw());
    }

    @Test
    void duplicateRegistrationLooksIdenticalAndTheOwnerIsToldWithoutAToken() {
        String email = uniqueEmail();
        Res first = register(email, PASSWORD);
        smtp().await(email); // verification mail #1
        Res second = post("/api/auth/register", Map.of("email", email.toUpperCase(), "password", "Another-pass-9", "displayName", "Mallory", "consent", true));
        assertEquals(first.status(), second.status());
        assertEquals(first.raw(), second.raw(), "byte-identical answer");

        SmtpSink.Mail info = smtp().await(email, 1);
        assertTrue(info.subject().contains("đăng ký bằng email của bạn"), info.subject());
        assertFalse(info.body().contains("token="), "no verification token goes out for someone else's attempt");
        assertTrue(info.link().endsWith("/forgot-password"), info.link());
        assertEquals(2, smtp().to(email).size());

        assertEquals(1, su.queryForObject("select count(*) from users where lower(email) = ?", Integer.class, email));
        assertEquals(1, su.queryForObject("select count(*) from email_verifications where user_id = ?", Integer.class, userId(email)));
        assertEquals(200, login(email, PASSWORD).status(), "the original password still works");
        assertEquals(401, login(email, "Another-pass-9").status(), "the attacker's password does not");
    }

    @Test
    void unverifiedUserCanSignInButCannotCheckOut() {
        String email = uniqueEmail();
        SmtpSink.Mail mail = registerUnverified(email, PASSWORD);
        UUID id = userId(email);

        assertEquals(200, login(email, PASSWORD).status(), "unverified users may sign in");
        // the guard billing will call before creating an order (P5.1)
        try (var ignored = CurrentContextHolder.bind(id, null)) {
            assertThrows(EmailNotVerifiedException.class, () -> directory.requireVerified(id));
            assertFalse(directory.findById(id).orElseThrow().emailVerified());
        }
        // fail closed: nobody bound, or somebody else bound
        assertThrows(EmailNotVerifiedException.class, () -> directory.requireVerified(id), "no user context");
        try (var ignored = CurrentContextHolder.bind(UUID.randomUUID(), null)) {
            assertThrows(EmailNotVerifiedException.class, () -> directory.requireVerified(id), "another user's context");
        }

        assertEquals(204, post("/api/auth/verify-email", Map.of("token", mail.linkParam("token"))).status());
        try (var ignored = CurrentContextHolder.bind(id, null)) {
            directory.requireVerified(id); // no exception
            assertTrue(directory.findById(id).orElseThrow().emailVerified());
        }
    }

    @Test
    void verificationLinkWorksOnceAndGarbageIsRejected() {
        String email = uniqueEmail();
        SmtpSink.Mail mail = registerUnverified(email, PASSWORD);
        String token = mail.linkParam("token");

        Res ok = post("/api/auth/verify-email", Map.of("token", token));
        assertEquals(204, ok.status());
        assertNotNull(su.queryForObject("select email_verified_at from users where email = ?", java.sql.Timestamp.class, email));

        Res again = post("/api/auth/verify-email", Map.of("token", token));
        assertEquals(400, again.status());
        assertEquals("urn:e9x:problem:invalid-or-expired-token", again.problemType());
        assertEquals(400, post("/api/auth/verify-email", Map.of("token", "x")).status());
        assertEquals(400, post("/api/auth/verify-email", Map.of("token", "A".repeat(43))).status());
    }

    @Test
    void anExpiredVerificationLinkIsRejected() {
        String email = uniqueEmail();
        SmtpSink.Mail mail = registerUnverified(email, PASSWORD);
        su.update("update email_verifications set expires_at = now() - interval '1 minute' where user_id = ?", userId(email));
        assertEquals(400, post("/api/auth/verify-email", Map.of("token", mail.linkParam("token"))).status());
        assertNull(su.queryForObject("select email_verified_at from users where email = ?", java.sql.Timestamp.class, email));
    }

    @Test
    void resendIssuesANewLinkAndRetiresTheOldOne() {
        String email = uniqueEmail();
        SmtpSink.Mail first = registerUnverified(email, PASSWORD);
        Res r = post("/api/auth/verify-email/resend", Map.of("email", email));
        assertEquals(202, r.status());
        SmtpSink.Mail second = smtp().await(email, 1);
        assertFalse(first.linkParam("token").equals(second.linkParam("token")));
        assertEquals(400, post("/api/auth/verify-email", Map.of("token", first.linkParam("token"))).status(), "old link retired");
        assertEquals(204, post("/api/auth/verify-email", Map.of("token", second.linkParam("token"))).status());
    }

    @Test
    void resendDoesNotRevealWhetherTheAddressExistsOrIsVerified() {
        String verified = registerVerified(uniqueEmail(), PASSWORD);
        int before = smtp().to(verified).size();
        String unknown = uniqueEmail();
        Res a = post("/api/auth/verify-email/resend", Map.of("email", verified));
        Res b = post("/api/auth/verify-email/resend", Map.of("email", unknown));
        assertEquals(202, a.status());
        assertEquals(a.status(), b.status());
        assertEquals(a.raw(), b.raw());
        assertEquals(0, smtp().countAfterSettling(unknown));
        assertEquals(before, smtp().to(verified).size(), "a verified account gets no mail");
    }

    @Test
    void resendIsRateLimitedPerAddressWithTheSameAnswerForUnknownAddresses() {
        String unknown = uniqueEmail();
        for (int i = 0; i < 3; i++) {
            assertEquals(202, post("/api/auth/verify-email/resend", Map.of("email", unknown)).status());
        }
        Res limited = post("/api/auth/verify-email/resend", Map.of("email", unknown));
        assertEquals(429, limited.status());
        assertNotNull(limited.headers().getFirst("Retry-After"));
    }

    @Test
    void aMailServerOutageDoesNotRollBackRegistration() {
        smtp().setDown(true);
        try {
            String email = uniqueEmail();
            Res r = register(email, PASSWORD);
            assertEquals(201, r.status(), r.raw());
            assertEquals(1, su.queryForObject("select count(*) from users where email = ?", Integer.class, email));
            assertEquals(0, smtp().countAfterSettling(email));
            // and the way back: once the server is up again the person can ask for the link
            smtp().setDown(false);
            assertEquals(202, post("/api/auth/verify-email/resend", Map.of("email", email)).status());
            assertTrue(smtp().await(email).link().contains("/verify-email?token="));
        } finally {
            smtp().setDown(false);
        }
    }

    /** A click confirms an account someone else may have registered: the warning comes before the link, in both languages. */
    @Test
    void theVerificationMailWarnsBeforeTheLinkNotToClickWhatYouDidNotAskFor() {
        String vi = uniqueEmail();
        register(vi, PASSWORD);
        String body = smtp().await(vi).body();
        assertTrue(body.indexOf("đừng bấm") >= 0 && body.indexOf("đừng bấm") < body.indexOf("/verify-email?token="), body);

        String en = uniqueEmail();
        post("/api/auth/register", Map.of("email", en, "password", PASSWORD, "displayName", "Ann", "consent", true, "locale", "en"));
        body = smtp().await(en).body();
        assertTrue(body.indexOf("do not click") >= 0 && body.indexOf("do not click") < body.indexOf("/verify-email?token="), body);
    }
}
