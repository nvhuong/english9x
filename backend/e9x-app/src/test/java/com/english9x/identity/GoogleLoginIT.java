package com.english9x.identity;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.english9x.support.FakeOidcProvider;
import com.english9x.support.FakeOidcProvider.Scenario;
import com.english9x.support.SmtpSink;
import com.english9x.support.TestAuth;
import java.net.URI;
import java.net.URLDecoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.util.Map;
import java.util.UUID;
import java.util.function.UnaryOperator;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

/** Google sign-in against an in-JVM OpenID provider: the real redirect dance, state, nonce, PKCE, linking rules. */
class GoogleLoginIT extends IdentityIT {

    static final FakeOidcProvider IDP = new FakeOidcProvider();

    @DynamicPropertySource
    static void google(DynamicPropertyRegistry registry) {
        registry.add("e9x.auth.google.client-id", () -> FakeOidcProvider.CLIENT_ID);
        registry.add("e9x.auth.google.client-secret", () -> FakeOidcProvider.CLIENT_SECRET);
        registry.add("e9x.auth.google.authorization-uri", () -> IDP.base() + "/authorize");
        registry.add("e9x.auth.google.token-uri", () -> IDP.base() + "/token");
        registry.add("e9x.auth.google.jwk-set-uri", () -> IDP.base() + "/jwks");
        registry.add("e9x.auth.google.issuer", IDP::issuer);
    }

    @LocalServerPort
    int port;

    private final HttpClient client = HttpClient.newBuilder().followRedirects(HttpClient.Redirect.NEVER).build();

    /** The browser-binding cookie of the latest finished sign-in, as the browser would hold it. */
    private String browser;
    private String browserSetCookie;

    private HttpResponse<String> rawGet(String url, String cookie) throws Exception {
        HttpRequest.Builder b = HttpRequest.newBuilder(URI.create(url)).GET();
        if (cookie != null) {
            b.header("Cookie", cookie);
        }
        return client.send(b.build(), HttpResponse.BodyHandlers.ofString());
    }

    private static String location(HttpResponse<?> r) {
        return r.headers().firstValue("Location").orElseThrow(() -> new AssertionError("no Location, status " + r.statusCode()));
    }

    /** Runs start -> provider -> callback and returns where the callback sent the browser. */
    private String signIn(Scenario s, UnaryOperator<String> callbackUrl, boolean sendCookie) throws Exception {
        IDP.willSignIn(s);
        HttpResponse<String> start = rawGet("http://localhost:" + port + "/api/auth/oauth2/google", null);
        assertEquals(302, start.statusCode());
        String cookie = start.headers().allValues("Set-Cookie").stream().filter(c -> c.startsWith("JSESSIONID")).findFirst()
                .map(c -> c.split(";")[0]).orElse(null);
        HttpResponse<String> authorize = rawGet(location(start), null);
        assertEquals(302, authorize.statusCode());
        String back = location(authorize);
        assertTrue(back.startsWith(TestAuth.BASE_URL + "/api/auth/oauth2/callback/google?"), "redirect URI comes from configuration: " + back);
        String local = "http://localhost:" + port + back.substring(TestAuth.BASE_URL.length());
        HttpResponse<String> callback = rawGet(callbackUrl.apply(local), sendCookie ? cookie : null);
        assertEquals(302, callback.statusCode(), callback.body());
        assertEquals("no-store", callback.headers().firstValue("Cache-Control").orElse(null));
        assertEquals("no-referrer", callback.headers().firstValue("Referrer-Policy").orElse(null));
        browserSetCookie = callback.headers().allValues("Set-Cookie").stream().filter(c -> c.startsWith("__Host-")).findFirst().orElse(null);
        browser = browserSetCookie == null ? null : browserSetCookie.split(";")[0];
        return location(callback);
    }

    private String signIn(Scenario s) throws Exception {
        return signIn(s, u -> u, true);
    }

    private static Scenario scenario(String email, boolean verified) {
        Scenario s = new Scenario();
        s.email = email;
        s.emailVerified = verified;
        return s;
    }

    private static String codeOf(String location) {
        assertTrue(location.startsWith(TestAuth.BASE_URL + "/login/google?code="), "expected a hand-over to the SPA, got " + location);
        return URLDecoder.decode(location.substring(location.indexOf("code=") + 5), StandardCharsets.UTF_8);
    }

    private static void assertRefused(String location) {
        assertTrue(location.startsWith(TestAuth.BASE_URL + "/login?error="), location);
        assertFalse(location.contains("code="), location);
    }

    private Res exchange(String code) {
        return exchange(code, browser);
    }

    private Res exchange(String code, String cookie) {
        return post("/api/auth/oauth/exchange", Map.of("code", code), cookie == null ? Map.of() : Map.of("Cookie", cookie));
    }

    private Res consent(String code, Object consent, String cookie) {
        return post("/api/auth/oauth/consent", Map.of("code", code, "consent", consent), cookie == null ? Map.of() : Map.of("Cookie", cookie));
    }

    /** What the page does: exchange, and when the server asks for consent, give it. */
    private Res finishWithConsent(String code) {
        Res r = exchange(code);
        assertEquals(403, r.status(), r.raw());
        assertEquals("urn:e9x:problem:consent-required", r.problemType());
        return consent(code, true, browser);
    }

    private java.sql.Timestamp consentAt(UUID id) {
        return su.queryForObject("select consent_at from users where id = ?", java.sql.Timestamp.class, id);
    }

    private int tokensOf(UUID id) {
        return su.queryForObject("select count(*) from refresh_tokens where user_id = ?", Integer.class, id);
    }

    // ---- tests ------------------------------------------------------------------------------------------------------

    @Test
    void theStartRedirectCarriesStateNonceAndPkceAndTheConfiguredRedirectUri() throws Exception {
        HttpResponse<String> start = rawGet("http://localhost:" + port + "/api/auth/oauth2/google", null);
        assertEquals(302, start.statusCode());
        String loc = location(start);
        assertTrue(loc.startsWith(IDP.base() + "/authorize?"), loc);
        for (String p : new String[] {"state=", "nonce=", "code_challenge=", "code_challenge_method=S256", "response_type=code",
                "client_id=" + FakeOidcProvider.CLIENT_ID, "scope=openid%20email%20profile"}) {
            assertTrue(loc.contains(p), p + " in " + loc);
        }
        assertTrue(loc.contains("redirect_uri=" + TestAuth.BASE_URL + "/api/auth/oauth2/callback/google"), loc);
    }

    @Test
    void aNewGoogleUserGetsAnAccountAndTheNextSignInReusesIt() throws Exception {
        String email = uniqueEmail();
        Scenario s = scenario(email, true);
        String loc = signIn(s);
        assertFalse(loc.contains("eyJ") || loc.toLowerCase().contains("refresh") || loc.contains("access"), "no token in the URL: " + loc);
        String code = codeOf(loc);
        UUID id = userId(email);
        assertNull(consentAt(id), "nobody agreed to anything yet");
        Res tokens = finishWithConsent(code);
        assertEquals(200, tokens.status(), tokens.raw());
        assertNotNull(consentAt(id), "agreed now");
        String access = tokens.body().get("accessToken").asText();
        Res me = get("/api/auth/me", access);
        assertEquals(email, me.body().get("email").asText());
        assertTrue(me.body().get("emailVerified").asBoolean(), "Google asserted the mailbox");
        assertEquals(id.toString(), me.body().get("id").asText());
        assertNull(su.queryForObject("select password_hash from users where id = ?", String.class, id));
        assertEquals(1, su.queryForObject("select count(*) from auth_identities where user_id = ? and provider = 'google'", Integer.class, id));

        // second time: same person, same account, no duplicate
        Res again = exchange(codeOf(signIn(s)));
        assertEquals(200, again.status());
        assertEquals(id.toString(), get("/api/auth/me", again.body().get("accessToken").asText()).body().get("id").asText());
        assertEquals(1, su.queryForObject("select count(*) from users where lower(email) = ?", Integer.class, email));
    }

    @Test
    void linksExistingEmail() throws Exception {
        String email = registerVerified(uniqueEmail(), PASSWORD);
        UUID id = userId(email);
        Scenario s = scenario(email, true);
        Res tokens = exchange(codeOf(signIn(s)));
        assertEquals(200, tokens.status(), tokens.raw());
        assertEquals(id.toString(), get("/api/auth/me", tokens.body().get("accessToken").asText()).body().get("id").asText(),
                "signed in as the EXISTING account");
        assertEquals(1, su.queryForObject("select count(*) from users where lower(email) = ?", Integer.class, email));
        assertEquals(s.sub, su.queryForObject("select subject from auth_identities where user_id = ?", String.class, id));
        assertEquals(200, login(email, PASSWORD).status(), "a verified account keeps its password after linking");
    }

    @Test
    void anEmailTheProviderDoesNotVouchForNeverLinksAndNeverCreates() throws Exception {
        String existing = registerVerified(uniqueEmail(), PASSWORD);
        String loc = signIn(scenario(existing, false));
        assertRefused(loc);
        assertTrue(loc.contains("google_email_unverified"), loc);
        assertEquals(0, su.queryForObject("select count(*) from auth_identities where user_id = ?", Integer.class, userId(existing)),
                "nothing was linked");
        assertEquals(200, login(existing, PASSWORD).status(), "and the account is untouched");

        String fresh = uniqueEmail();
        assertRefused(signIn(scenario(fresh, false)));
        assertEquals(0, su.queryForObject("select count(*) from users where lower(email) = ?", Integer.class, fresh), "no account created either");
    }

    @Test
    void preHijackAnAttackersPasswordAndSessionDieWhenTheRealOwnerLinksGoogle() throws Exception {
        // 1. the attacker registers the victim's address (unverified) with their own password and signs in
        String victim = uniqueEmail();
        SmtpSink.Mail pendingMail = registerUnverified(victim, "Attacker-password-1");
        Res attacker = login(victim, "Attacker-password-1");
        assertEquals(200, attacker.status(), "unverified accounts can sign in, so the attacker holds a live session");
        String attackerRefresh = attacker.body().get("refreshToken").asText();

        // 2. the real owner signs in with Google (the provider asserts the mailbox is theirs)
        UUID id = userId(victim);
        assertNotNull(consentAt(id), "the attacker agreed when registering");
        Scenario realOwner = scenario(victim, true);
        realOwner.name = "The Real Owner";
        String code = codeOf(signIn(realOwner));
        assertNull(consentAt(id), "that consent was the attacker's, not the owner's");
        assertEquals("The Real Owner", su.queryForObject("select display_name from users where id = ?", String.class, id), "nor is the name theirs to keep");
        int before = tokensOf(id);
        Res refused = exchange(code);
        assertEquals(403, refused.status(), refused.raw());
        assertEquals(before, tokensOf(id), "no token without the owner's own consent");
        Res owner = consent(code, true, browser);
        assertEquals(200, owner.status(), owner.raw());
        assertNotNull(consentAt(id));

        // 3. the attacker's foothold is gone
        assertEquals(401, login(victim, "Attacker-password-1").status(), "the attacker's password no longer works");
        assertEquals(401, post("/api/auth/refresh", Map.of("refreshToken", attackerRefresh)).status(), "nor does their session");
        assertEquals(400, post("/api/auth/verify-email", Map.of("token", pendingMail.linkParam("token"))).status(), "nor the pending link");
        assertNull(su.queryForObject("select password_hash from users where id = ?", String.class, userId(victim)));
        // 4. the owner keeps the account and can add a password through the reset mail
        assertTrue(get("/api/auth/me", owner.body().get("accessToken").asText()).body().get("emailVerified").asBoolean());
        post("/api/auth/password-reset/request", Map.of("email", victim));
        SmtpSink.Mail reset = smtp().awaitSubject(victim, "Đặt lại mật khẩu");
        assertEquals(204, post("/api/auth/password-reset/confirm", Map.of("token", reset.linkParam("token"), "newPassword", "Owner-new-pass-1")).status());
        assertEquals(200, login(victim, "Owner-new-pass-1").status());
    }

    // ---- the code belongs to the browser that finished the sign-in (login CSRF) -----------------------------------

    @Test
    void theBindingCookieIsHostOnlySecureHttpOnlyStrict() throws Exception {
        signIn(scenario(uniqueEmail(), true));
        assertNotNull(browserSetCookie, "the callback sets the binding cookie");
        String c = browserSetCookie.toLowerCase();
        assertTrue(browserSetCookie.startsWith("__Host-e9x-login="), browserSetCookie);
        for (String attribute : new String[] {"; path=/", "; secure", "; httponly", "; samesite=strict", "; max-age="}) {
            assertTrue(c.contains(attribute), attribute + " in " + browserSetCookie);
        }
        assertFalse(c.contains("domain="), browserSetCookie);
    }

    @Test
    void anAttackersCodeDoesNotWorkInTheVictimsBrowser() throws Exception {
        // the attacker signs in with their own Google account and stops at the redirect, keeping the code
        String attacker = registerVerified(uniqueEmail(), PASSWORD);
        String code = codeOf(signIn(scenario(attacker, true)));
        String attackersBrowser = browser;

        // the victim opens the link: without a cookie, then with the cookie of the victim's own earlier sign-in
        Res noCookie = exchange(code, null);
        assertEquals(400, noCookie.status(), noCookie.raw());
        assertEquals("urn:e9x:problem:invalid-login-code", noCookie.problemType());
        String victim = registerVerified(uniqueEmail(), PASSWORD);
        String victimsCode = codeOf(signIn(scenario(victim, true)));
        String victimsBrowser = browser;
        assertEquals(400, exchange(code, victimsBrowser).status(), "someone else's browser");
        assertEquals(400, consent(code, true, victimsBrowser).status(), "the consent door checks it too");
        assertEquals(400, exchange(code, "__Host-e9x-login=" + "A".repeat(43)).status(), "a made-up cookie");
        assertEquals(0, tokensOf(userId(attacker)) - su.queryForObject("select count(*) from refresh_tokens where user_id = ? and revoked_at is not null", Integer.class, userId(attacker)),
                "nothing was minted for the attacker's account");

        // the probing did not burn it for its rightful browser, and the victim's own code still works
        assertEquals(200, exchange(code, attackersBrowser).status());
        assertEquals(200, exchange(victimsCode, victimsBrowser).status());
    }

    @Test
    void aSuccessfulExchangeClearsTheBindingCookie() throws Exception {
        String code = codeOf(signIn(scenario(registerVerified(uniqueEmail(), PASSWORD), true)));
        Res r = exchange(code);
        assertEquals(200, r.status(), r.raw());
        String cleared = r.headers().get("Set-Cookie").stream().filter(c -> c.startsWith("__Host-e9x-login=")).findFirst().orElseThrow();
        assertTrue(cleared.contains("Max-Age=0"), cleared);
        assertTrue(cleared.contains("Secure") && cleared.contains("Path=/"), "a __Host- cookie is only replaced with its own attributes: " + cleared);
    }

    // ---- consent is a server-checked step ----------------------------------------------------------------------------

    @Test
    void noTokenEverWithoutConsentAndTheStepIsChecked() throws Exception {
        String email = uniqueEmail();
        String code = codeOf(signIn(scenario(email, true)));
        UUID id = userId(email);

        Res first = exchange(code);
        assertEquals(403, first.status(), first.raw());
        assertEquals("urn:e9x:problem:consent-required", first.problemType());
        assertFalse(first.raw().contains("accessToken"));
        assertEquals(0, tokensOf(id));

        Res notTicked = consent(code, false, browser);
        assertEquals(400, notTicked.status(), notTicked.raw());
        assertTrue(notTicked.raw().contains("\"field\":\"consent\""), notTicked.raw());
        assertNull(consentAt(id));
        assertEquals(0, tokensOf(id));

        Res agreed = consent(code, true, browser);
        assertEquals(200, agreed.status(), agreed.raw());
        assertNotNull(consentAt(id));
        assertEquals(1, tokensOf(id));
        assertEquals(400, consent(code, true, browser).status(), "the code was spent");

        // next time: the consent is on record, no new step
        assertEquals(200, exchange(codeOf(signIn(scenario(email, true)))).status());
    }

    @Test
    void walkingAwayFromTheStepAndComingBackAsksAgain() throws Exception {
        String email = uniqueEmail();
        Scenario s = scenario(email, true);
        assertEquals(403, exchange(codeOf(signIn(s))).status());
        Res again = exchange(codeOf(signIn(s)));
        assertEquals(403, again.status(), again.raw());
        assertEquals(1, su.queryForObject("select count(*) from users where lower(email) = ?", Integer.class, email));
    }

    @Test
    void theOtherTokenEndpointsRefuseAUserWithoutConsentToo() throws Exception {
        // a Google account that never agreed gets a password through the reset mail: it still has not agreed
        String email = uniqueEmail();
        signIn(scenario(email, true));
        UUID id = userId(email);
        post("/api/auth/password-reset/request", Map.of("email", email));
        String token = smtp().await(email).linkParam("token");
        assertEquals(204, post("/api/auth/password-reset/confirm", Map.of("token", token, "newPassword", "Owner-new-pass-1")).status());
        Res login = login(email, "Owner-new-pass-1");
        assertEquals(403, login.status(), login.raw());
        assertEquals("urn:e9x:problem:consent-required", login.problemType());
        assertEquals(0, tokensOf(id));

        // and rotation will not extend a session of someone whose consent is not on record
        String other = registerVerified(uniqueEmail(), PASSWORD);
        String refresh = login(other, PASSWORD).body().get("refreshToken").asText();
        su.update("update users set consent_at = null where id = ?", userId(other));
        assertEquals(401, post("/api/auth/refresh", Map.of("refreshToken", refresh)).status());
    }

    @Test
    void linkingAnAccountWhoseConsentIsOnRecordNeedsNoNewStep() throws Exception {
        String email = registerVerified(uniqueEmail(), PASSWORD);
        java.sql.Timestamp before = consentAt(userId(email));
        assertEquals(200, exchange(codeOf(signIn(scenario(email, true)))).status());
        assertEquals(before, consentAt(userId(email)), "the original consent stays");
    }

    // ---- pre-hijack through the verification link ---------------------------------------------------------------------

    @Test
    void aVerifyClickKeepsTheSessionOfTheRegistrantWhichIsTheNormalCase() {
        String email = uniqueEmail();
        SmtpSink.Mail mail = registerUnverified(email, PASSWORD);
        String refresh = login(email, PASSWORD).body().get("refreshToken").asText();
        assertEquals(204, post("/api/auth/verify-email", Map.of("token", mail.linkParam("token"))).status());
        assertEquals(200, post("/api/auth/refresh", Map.of("refreshToken", refresh)).status(), "the person's own session survives");
        assertEquals(200, login(email, PASSWORD).status(), "and their password");
    }

    @Test
    void linkingGoogleEndsEverySessionOfAnAccountAVictimVerifiedForAnAttacker() throws Exception {
        // the attacker registers the victim's address and signs in; the victim clicks the verification link
        String victim = uniqueEmail();
        SmtpSink.Mail mail = registerUnverified(victim, "Attacker-password-1");
        Res attacker = login(victim, "Attacker-password-1");
        String attackerRefresh = attacker.body().get("refreshToken").asText();
        assertEquals(204, post("/api/auth/verify-email", Map.of("token", mail.linkParam("token"))).status());

        // the victim signs in with Google: the account is verified, so it is a plain link
        Res owner = exchange(codeOf(signIn(scenario(victim, true))));
        assertEquals(200, owner.status(), owner.raw());

        assertEquals(401, post("/api/auth/refresh", Map.of("refreshToken", attackerRefresh)).status(), "no attacker session is left");
        assertEquals(200, post("/api/auth/refresh", Map.of("refreshToken", owner.body().get("refreshToken").asText())).status(), "the owner's is fine");
        // The password stays: a verify click cannot tell this case from a normal user adding Google (linksExistingEmail).
        // So the owner is told, in a mail that says what to do. See the report.
        assertEquals(200, login(victim, "Attacker-password-1").status(), "documented residual: the password survives until the owner resets it");
        SmtpSink.Mail notice = smtp().awaitSubject(victim, "liên kết");
        assertTrue(notice.body().contains("/forgot-password"), notice.body());
    }

    @Test
    void aNeutralisedAccountsOwnerIsToldToo() throws Exception {
        String victim = uniqueEmail();
        registerUnverified(victim, "Attacker-password-1");
        signIn(scenario(victim, true));
        assertNotNull(smtp().awaitSubject(victim, "liên kết"));
    }

    @Test
    void aTamperedStateIsRefusedAndCreatesNothing() throws Exception {
        String email = uniqueEmail();
        assertRefused(signIn(scenario(email, true), u -> u.replaceAll("state=[^&]+", "state=forged"), true));
        Scenario evil = scenario(email, true);
        evil.stateOverride = "attacker-state";
        assertRefused(signIn(evil));
        assertEquals(0, su.queryForObject("select count(*) from users where lower(email) = ?", Integer.class, email));
    }

    @Test
    void aCallbackWithoutTheBrowsersSessionIsRefused() throws Exception {
        String email = uniqueEmail();
        assertRefused(signIn(scenario(email, true), u -> u, false)); // e.g. a link followed in another browser
        assertEquals(0, su.queryForObject("select count(*) from users where lower(email) = ?", Integer.class, email));
    }

    @Test
    void aWrongNonceInTheIdTokenIsRefused() throws Exception {
        String email = uniqueEmail();
        Scenario s = scenario(email, true);
        s.nonceOverride = "replayed-nonce";
        assertRefused(signIn(s));
        assertEquals(0, su.queryForObject("select count(*) from users where lower(email) = ?", Integer.class, email));
    }

    @Test
    void anIdTokenForAnotherClientOrIssuerIsRefused() throws Exception {
        String email = uniqueEmail();
        Scenario audience = scenario(email, true);
        audience.audience = "somebody-elses-client";
        assertRefused(signIn(audience));
        Scenario issuer = scenario(email, true);
        issuer.issuerOverride = "https://evil.example";
        assertRefused(signIn(issuer));
        assertEquals(0, su.queryForObject("select count(*) from users where lower(email) = ?", Integer.class, email));
    }

    @Test
    void theHandOverCodeWorksOnceOnly() throws Exception {
        String code = codeOf(signIn(scenario(registerVerified(uniqueEmail(), PASSWORD), true)));
        assertEquals(200, exchange(code).status());
        Res second = exchange(code);
        assertEquals(400, second.status());
        assertEquals("urn:e9x:problem:invalid-login-code", second.problemType());
        assertEquals(400, exchange("A".repeat(43)).status());
    }

    @Test
    void aCodeFromBeforeAPasswordChangeIsRefused() throws Exception {
        String email = registerVerified(uniqueEmail(), PASSWORD);
        String code = codeOf(signIn(scenario(email, true)));
        post("/api/auth/password-reset/request", Map.of("email", email));
        String token = smtp().awaitSubject(email, "Đặt lại mật khẩu").linkParam("token");
        assertEquals(204, post("/api/auth/password-reset/confirm", Map.of("token", token, "newPassword", "Owner-new-pass-1")).status());
        Res exchanged = exchange(code);
        assertEquals(400, exchanged.status(), exchanged.raw());
        assertEquals("urn:e9x:problem:invalid-login-code", exchanged.problemType());
        assertEquals(0, su.queryForObject("select count(*) from refresh_tokens where user_id = ? and revoked_at is null", Integer.class, userId(email)));
    }

    @Test
    void theAuthorizationSessionDoesNotOutliveTheCallback() throws Exception {
        IDP.willSignIn(scenario(uniqueEmail(), true));
        HttpResponse<String> start = rawGet("http://localhost:" + port + "/api/auth/oauth2/google", null);
        String cookie = start.headers().allValues("Set-Cookie").stream().filter(c -> c.startsWith("JSESSIONID")).findFirst().orElseThrow().split(";")[0];
        String back = location(rawGet(location(start), null));
        String local = "http://localhost:" + port + back.substring(TestAuth.BASE_URL.length());
        assertTrue(location(rawGet(local, cookie)).contains("/login/google?code="));
        assertRefused(location(rawGet(local, cookie))); // replaying the callback finds no stored request
    }

    @Test
    void configEndpointReportsGoogleAsEnabled() {
        assertTrue(get("/api/auth/config", null).body().get("googleEnabled").asBoolean());
    }
}
