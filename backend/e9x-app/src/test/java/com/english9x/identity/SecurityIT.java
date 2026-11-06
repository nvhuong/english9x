package com.english9x.identity;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.english9x.support.TestAuth;
import com.nimbusds.jose.JWSAlgorithm;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpMethod;

/** Deny-by-default, token validation, error shape, headers, actuator, CORS. */
class SecurityIT extends IdentityIT {

    private static void assertProblem(Res r, int status, String type) {
        assertEquals(status, r.status(), r.raw());
        assertTrue(r.headers().getContentType().toString().startsWith("application/problem+json"), r.headers().getContentType().toString());
        for (String k : List.of("type", "title", "status", "detail", "errors")) {
            assertTrue(r.body().has(k), k + " missing in " + r.raw());
        }
        assertEquals(type, r.problemType());
        assertTrue(r.body().get("errors").isArray());
    }

    @Test
    void apiIsDeniedByDefaultWithAProblem() {
        for (String path : List.of("/api/auth/me", "/api/anything", "/api/sweep/classes/" + UUID.randomUUID(), "/api/test/seed/ping")) {
            assertProblem(send(HttpMethod.GET, path, null, null, Map.of()), 401, "urn:e9x:problem:unauthorized");
        }
        assertProblem(send(HttpMethod.POST, "/api/anything", "{}", null, Map.of()), 401, "urn:e9x:problem:unauthorized");
        assertEquals("Bearer", send(HttpMethod.GET, "/api/anything", null, null, Map.of()).headers().getFirst("WWW-Authenticate"));
    }

    @Test
    void theExplicitPublicListIsReachableWithoutAToken() {
        assertEquals(200, get("/api/platform/ping", null).status());
        assertEquals(200, get("/api/auth/config", null).status());
        assertEquals(200, get("/actuator/health", null).status());
        assertTrue(get("/v3/api-docs", null).status() != 401, "reachable (or 404 when disabled by config), never 401");
        // the auth endpoints answer (400 for an empty body), they do not demand a token
        for (String path : List.of("/api/auth/register", "/api/auth/login", "/api/auth/refresh", "/api/auth/logout",
                "/api/auth/verify-email", "/api/auth/verify-email/resend", "/api/auth/password-reset/request",
                "/api/auth/password-reset/confirm", "/api/auth/oauth/exchange")) {
            assertEquals(400, post(path, "{}").status(), path);
        }
    }

    @Test
    void aValidTokenOpensTheApi() {
        String email = registerVerified(uniqueEmail(), PASSWORD);
        Res me = get("/api/auth/me", TestAuth.accessToken(userId(email)));
        assertEquals(200, me.status(), me.raw());
        assertEquals(email, me.body().get("email").asText());
        assertTrue(me.body().get("emailVerified").asBoolean());
        assertFalse(me.raw().contains("password"), "no hash in the profile: " + me.raw());
    }

    @Test
    void badTokensAreAll401WithTheSameProblem() {
        UUID id = UUID.randomUUID();
        Instant future = Instant.now().plusSeconds(600);
        List<String> bad = List.of(
                TestAuth.unsignedToken(id.toString()),                                                        // alg: none
                TestAuth.token(id.toString(), "someone-else", future, JWSAlgorithm.HS256, TestAuth.JWT_SECRET), // wrong issuer
                TestAuth.token(id.toString(), TestAuth.ISSUER, Instant.now().minusSeconds(120), JWSAlgorithm.HS256, TestAuth.JWT_SECRET), // expired
                TestAuth.token(id.toString(), TestAuth.ISSUER, future, JWSAlgorithm.HS256, "another-secret-another-secret-123456"),       // wrong key
                TestAuth.token(id.toString(), TestAuth.ISSUER, future, JWSAlgorithm.HS512, TestAuth.JWT_SECRET + TestAuth.JWT_SECRET),  // other algorithm
                TestAuth.accessToken(id).substring(0, 40),                                                    // truncated
                "not.a.jwt", "x");
        for (String token : bad) {
            Res r = get("/api/auth/me", token);
            assertProblem(r, 401, "urn:e9x:problem:unauthorized");
            assertEquals("Authentication is required.", r.body().get("detail").asText(), "no hint about what was wrong");
        }
        // a tampered payload: change the subject, keep the signature
        String[] parts = TestAuth.accessToken(id).split("\\.");
        String forgedPayload = java.util.Base64.getUrlEncoder().withoutPadding()
                .encodeToString(("{\"sub\":\"" + UUID.randomUUID() + "\",\"iss\":\"english9x\",\"exp\":" + future.getEpochSecond() + "}").getBytes());
        assertProblem(get("/api/auth/me", parts[0] + "." + forgedPayload + "." + parts[2]), 401, "urn:e9x:problem:unauthorized");
    }

    @Test
    void aTokenInTheQueryStringOrACookieIsNotAccepted() {
        String token = TestAuth.accessToken(userId(registerVerified(uniqueEmail(), PASSWORD)));
        assertEquals(401, send(HttpMethod.GET, "/api/auth/me?access_token=" + token, null, null, Map.of()).status());
        assertEquals(401, send(HttpMethod.GET, "/api/auth/me", null, null, Map.of("Cookie", "access_token=" + token + "; token=" + token)).status());
    }

    @Test
    void aSubjectThatIsNotAUserIdIs401NotA500() {
        String token = TestAuth.token("not-a-uuid", TestAuth.ISSUER, Instant.now().plusSeconds(600), JWSAlgorithm.HS256, TestAuth.JWT_SECRET);
        assertProblem(get("/api/auth/me", token), 401, "urn:e9x:problem:unauthorized");
    }

    @Test
    void anOrganisationContextHeaderIsRefusedUntilP15ValidatesIt() {
        String token = TestAuth.accessToken(userId(registerVerified(uniqueEmail(), PASSWORD)));
        Res r = send(HttpMethod.GET, "/api/auth/me", null, token, Map.of("X-Org-Context", UUID.randomUUID().toString()));
        assertProblem(r, 403, "urn:e9x:problem:forbidden");
        assertEquals(200, get("/api/auth/me", token).status(), "without the header the same token works");
    }

    @Test
    void securityAnd404AndValidationErrorsShareOneShape() {
        String token = TestAuth.accessToken(userId(registerVerified(uniqueEmail(), PASSWORD)));
        Res notFound = get("/api/does-not-exist", token);
        assertProblem(notFound, 404, "urn:e9x:problem:not-found");
        Res unauth = get("/api/auth/me", null);
        for (String k : List.of("type", "title", "status", "detail", "errors")) {
            assertTrue(unauth.body().has(k) && notFound.body().has(k), k);
        }
    }

    @Test
    void securityHeadersAreSent() {
        Res r = get("/api/platform/ping", null);
        assertEquals("nosniff", r.headers().getFirst("X-Content-Type-Options"));
        assertEquals("DENY", r.headers().getFirst("X-Frame-Options"));
        assertEquals("no-referrer", r.headers().getFirst("Referrer-Policy"));
        String csp = r.headers().getFirst("Content-Security-Policy");
        assertNotNull(csp);
        assertTrue(csp.contains("default-src 'self'") && csp.contains("frame-ancestors 'none'") && !csp.contains("unsafe-eval"), csp);
        assertTrue(r.headers().getFirst("Cache-Control").contains("no-store"), "API answers are not cached");
        assertNotNull(r.headers().getFirst("Permissions-Policy"));
        // an error from the filter chain carries them too
        assertEquals("nosniff", get("/api/auth/me", null).headers().getFirst("X-Content-Type-Options"));
    }

    @Test
    void actuatorExposesHealthOnly() {
        assertEquals(200, get("/actuator/health", null).status());
        for (String p : List.of("/actuator/env", "/actuator/beans", "/actuator/heapdump", "/actuator/metrics", "/actuator/info", "/actuator")) {
            int status = get(p, TestAuth.accessToken(UUID.randomUUID())).status();
            assertTrue(status == 401 || status == 403 || status == 404, p + " -> " + status);
            assertTrue(get(p, null).status() != 200, p + " anonymous");
        }
    }

    @Test
    void noCrossOriginAccessIsGranted() {
        Res simple = send(HttpMethod.GET, "/api/platform/ping", null, null, Map.of("Origin", "https://evil.example"));
        assertNull(simple.headers().getFirst("Access-Control-Allow-Origin"));
        Res preflight = send(HttpMethod.OPTIONS, "/api/auth/login", null, null, Map.of("Origin", "https://evil.example",
                "Access-Control-Request-Method", "POST", "Access-Control-Request-Headers", "content-type"));
        assertNull(preflight.headers().getFirst("Access-Control-Allow-Origin"));
        assertTrue(preflight.status() >= 400, "the preflight is refused: " + preflight.status());
    }

    @Test
    void theSpaStillLoadsAnonymouslyAndOtherMethodsOutsideApiAreDenied() {
        assertTrue(get("/login", null).status() != 401);
        int status = send(HttpMethod.POST, "/somewhere", "{}", null, Map.of()).status();
        assertTrue(status == 401 || status == 403, "denied, was " + status);
    }
}
