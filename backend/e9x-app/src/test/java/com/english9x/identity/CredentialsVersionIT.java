package com.english9x.identity;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.english9x.org.api.CurrentContextHolder;
import com.english9x.support.TestAuth;
import java.nio.charset.StandardCharsets;
import java.util.Base64;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * M11: an access token names the credentials version it was issued under, and every authenticated request compares it with the database.
 * What has no token (public routes, seed, listeners, jobs) never meets the check; {@code SeedEnabledIT}, {@code JobQueueIT} and {@code ReviewEventsIT}
 * run the real seed, worker and listeners, and the last test here shows why, with a bound thread and a stale account.
 */
class CredentialsVersionIT extends IdentityIT {

    @Autowired
    JdbcTemplate app;

    @Autowired
    PlatformTransactionManager txManager;

    private static String claims(String jwt) {
        return new String(Base64.getUrlDecoder().decode(jwt.split("\\.")[1]), StandardCharsets.UTF_8);
    }

    @Test
    void aLoginAndARefreshCarryTheAccountsCurrentVersion() throws Exception {
        String email = registerVerified(uniqueEmail(), PASSWORD);
        UUID id = userId(email);
        su.update("update users set credentials_version = 41 where id = ?", id);
        Res session = login(email, PASSWORD);
        assertEquals(41, JSON.readTree(claims(session.body().get("accessToken").asText())).path("credentials_version").asLong());
        assertEquals(200, get("/api/auth/me", session.body().get("accessToken").asText()).status());

        Res next = post("/api/auth/refresh", Map.of("refreshToken", session.body().get("refreshToken").asText()));
        assertEquals(200, next.status(), next.raw());
        assertEquals(41, JSON.readTree(claims(next.body().get("accessToken").asText())).path("credentials_version").asLong());
        assertEquals(200, get("/api/auth/me", next.body().get("accessToken").asText()).status());
    }

    @Test
    void aTokenFromBeforeTheChangeOrWithoutTheClaimOrForNoAccountAnswers401() {
        String email = registerVerified(uniqueEmail(), PASSWORD);
        UUID id = userId(email);
        assertEquals(200, get("/api/auth/me", TestAuth.accessToken(id)).status(), "a positive control: the helper mints what the app accepts");

        Res legacy = get("/api/auth/me", TestAuth.accessTokenWithoutCredentialsVersion(id));
        assertEquals(401, legacy.status(), "issued before this check: refused, so a refresh is forced");
        assertEquals("urn:e9x:problem:unauthorized", legacy.problemType());

        su.update("update users set credentials_version = credentials_version + 1 where id = ?", id);
        Res stale = get("/api/auth/me", TestAuth.accessToken(id));
        assertEquals(401, stale.status());
        assertEquals("Bearer", stale.headers().getFirst("WWW-Authenticate"));
        assertEquals(200, get("/api/auth/me", TestAuth.accessToken(id, TestAuth.FRESH_CREDENTIALS_VERSION + 1)).status());

        assertEquals(401, get("/api/auth/me", TestAuth.accessToken(UUID.randomUUID())).status(), "an account that no longer exists");
    }

    @Test
    void aClientWithAStaleTokenCanStillRefreshAndLogOutBecauseThoseCarryNoBearer() {
        String email = registerVerified(uniqueEmail(), PASSWORD);
        Res session = login(email, PASSWORD);
        su.update("update users set credentials_version = credentials_version + 1 where id = ?", userId(email));
        assertEquals(401, get("/api/auth/me", session.body().get("accessToken").asText()).status());
        // refresh tokens are revoked by the real paths (reset, link), not by this update: the routes themselves are open
        assertEquals(200, post("/api/auth/refresh", Map.of("refreshToken", session.body().get("refreshToken").asText())).status());
        assertEquals(204, post("/api/auth/logout", Map.of("refreshToken", session.body().get("refreshToken").asText())).status());
        assertEquals(200, get("/api/platform/ping", null).status(), "public routes need no token at all");
    }

    @Test
    void workWithoutARequestIsNotAffected() {
        // what a listener or a job does: binds the user itself, no JWT anywhere, and the version plays no part
        String email = registerVerified(uniqueEmail(), PASSWORD);
        UUID id = userId(email);
        su.update("update users set credentials_version = credentials_version + 7 where id = ?", id);
        try (CurrentContextHolder.Scope ignored = CurrentContextHolder.bind(id, null)) {
            String seen = new TransactionTemplate(txManager).execute(s -> app.queryForObject("select email from users where id = ?", String.class, id));
            assertNotNull(seen);
            assertTrue(seen.equalsIgnoreCase(email));
        }
    }
}
