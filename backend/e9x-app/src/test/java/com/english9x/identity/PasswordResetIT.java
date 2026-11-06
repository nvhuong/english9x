package com.english9x.identity;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.english9x.support.SmtpSink;
import com.english9x.support.TestAuth;
import java.util.Map;
import org.junit.jupiter.api.Test;

class PasswordResetIT extends IdentityIT {

    private static final String NEW_PASSWORD = "Brand-new-pass-2";

    private SmtpSink.Mail requestReset(String email, int alreadyHave) {
        Res r = post("/api/auth/password-reset/request", Map.of("email", email));
        assertEquals(202, r.status(), r.raw());
        return smtp().await(email, alreadyHave);
    }

    private Res confirm(String token, String password) {
        return post("/api/auth/password-reset/confirm", Map.of("token", token, "newPassword", password));
    }

    @Test
    void oldPasswordAndTokensRejected() {
        String email = registerVerified(uniqueEmail(), PASSWORD);
        Res session = login(email, PASSWORD);
        String oldRefresh = session.body().get("refreshToken").asText();
        String oldAccess = session.body().get("accessToken").asText();
        int mails = smtp().to(email).size();

        SmtpSink.Mail mail = requestReset(email, mails);
        assertTrue(mail.subject().contains("Đặt lại mật khẩu"), mail.subject());
        assertTrue(mail.link().startsWith(TestAuth.BASE_URL + "/reset-password?token="), mail.link());
        String token = mail.linkParam("token");

        assertEquals(204, confirm(token, NEW_PASSWORD).status());

        assertEquals(401, login(email, PASSWORD).status(), "the old password is rejected");
        assertEquals(200, login(email, NEW_PASSWORD).status(), "the new one works");
        Res refresh = post("/api/auth/refresh", Map.of("refreshToken", oldRefresh));
        assertEquals(401, refresh.status(), "refresh tokens issued before the reset are revoked");
        Res reuse = confirm(token, "Yet-another-pass-3");
        assertEquals(400, reuse.status(), "the reset link is single use");
        assertEquals("urn:e9x:problem:invalid-or-expired-token", reuse.problemType());
        assertEquals(401, login(email, "Yet-another-pass-3").status());

        // Access tokens are stateless: the one issued before the reset keeps working until its 15 minutes are up.
        assertEquals(200, get("/api/auth/me", oldAccess).status());
    }

    @Test
    void anExpiredLinkIsRejectedAndNothingChanges() {
        String email = registerVerified(uniqueEmail(), PASSWORD);
        SmtpSink.Mail mail = requestReset(email, smtp().to(email).size());
        su.update("update password_resets set expires_at = now() - interval '1 second' where user_id = ?", userId(email));
        assertEquals(400, confirm(mail.linkParam("token"), NEW_PASSWORD).status());
        assertEquals(200, login(email, PASSWORD).status());
    }

    @Test
    void aNewRequestRetiresTheOlderLink() {
        String email = registerVerified(uniqueEmail(), PASSWORD);
        int n = smtp().to(email).size();
        SmtpSink.Mail first = requestReset(email, n);
        SmtpSink.Mail second = requestReset(email, n + 1);
        assertEquals(400, confirm(first.linkParam("token"), NEW_PASSWORD).status());
        assertEquals(204, confirm(second.linkParam("token"), NEW_PASSWORD).status());
    }

    @Test
    void aWeakNewPasswordIsRejectedWithoutSpendingTheLink() {
        String email = registerVerified(uniqueEmail(), PASSWORD);
        SmtpSink.Mail mail = requestReset(email, smtp().to(email).size());
        Res weak = confirm(mail.linkParam("token"), "short");
        assertEquals(400, weak.status());
        assertTrue(weak.raw().contains("\"field\":\"newPassword\""), weak.raw());
        Res tooLong = confirm(mail.linkParam("token"), "é".repeat(40));
        assertEquals(400, tooLong.status());
        assertEquals(204, confirm(mail.linkParam("token"), NEW_PASSWORD).status(), "the link still works");
    }

    @Test
    void requestingAResetDoesNotRevealWhetherTheAddressHasAnAccount() {
        String known = registerVerified(uniqueEmail(), PASSWORD);
        String unknown = uniqueEmail();
        Res a = post("/api/auth/password-reset/request", Map.of("email", known));
        Res b = post("/api/auth/password-reset/request", Map.of("email", unknown));
        assertEquals(202, a.status());
        assertEquals(a.status(), b.status());
        assertEquals(a.raw(), b.raw());
        assertEquals(0, smtp().countAfterSettling(unknown), "no mail for an unknown address");
        assertNotNull(smtp().await(known, 1));
    }

    @Test
    void resetRequestsAreRateLimitedPerAddress() {
        String email = uniqueEmail();
        for (int i = 0; i < 5; i++) {
            assertEquals(202, post("/api/auth/password-reset/request", Map.of("email", email)).status());
        }
        assertEquals(429, post("/api/auth/password-reset/request", Map.of("email", email)).status());
    }

    @Test
    void followingTheLinkAlsoProvesTheMailbox() {
        String email = uniqueEmail();
        registerUnverified(email, PASSWORD);
        SmtpSink.Mail mail = requestReset(email, smtp().to(email).size());
        assertEquals(204, confirm(mail.linkParam("token"), NEW_PASSWORD).status());
        assertFalse(su.queryForObject("select email_verified_at is null from users where email = ?", Boolean.class, email));
    }

    @Test
    void aGoogleOnlyAccountCanSetAPasswordThroughReset() {
        String email = uniqueEmail();
        su.update("insert into users(email, email_verified_at) values (?, now())", email);
        SmtpSink.Mail mail = requestReset(email, 0);
        assertEquals(204, confirm(mail.linkParam("token"), NEW_PASSWORD).status());
        assertEquals(200, login(email, NEW_PASSWORD).status());
    }
}
