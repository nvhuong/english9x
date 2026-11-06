package com.english9x.identity;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.Callable;
import java.util.concurrent.CyclicBarrier;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import org.junit.jupiter.api.Test;

class RefreshRotationIT extends IdentityIT {

    private record Session(String email, String access, String refresh) {}

    private Session signIn() {
        String email = registerVerified(uniqueEmail(), PASSWORD);
        Res r = login(email, PASSWORD);
        assertEquals(200, r.status(), r.raw());
        return new Session(email, r.body().get("accessToken").asText(), r.body().get("refreshToken").asText());
    }

    private Res refresh(String token) {
        return post("/api/auth/refresh", Map.of("refreshToken", token));
    }

    /** Pretends time passed: the token was rotated well outside the benign-race window. */
    private void ageRotation(String email) {
        su.update("update refresh_tokens set rotated_at = now() - interval '5 minutes' where rotated_at is not null and user_id = ?", userId(email));
    }

    @Test
    void loginIssuesAShortLivedAccessTokenAndAnOpaqueRefreshToken() {
        Session s = signIn();
        assertEquals(3, s.access().split("\\.").length, "a JWT");
        assertFalse(s.refresh().contains("."), "the refresh token is opaque, not a JWT");
        assertEquals(200, get("/api/auth/me", s.access()).status());
        Res login = login(s.email(), PASSWORD);
        assertEquals(900, login.body().get("expiresIn").asLong());
        assertEquals("Bearer", login.body().get("tokenType").asText());
        // only a digest of the refresh token is stored
        assertEquals(0, su.queryForObject("select count(*) from refresh_tokens where token_hash = ?", Integer.class, s.refresh().getBytes()));
    }

    @Test
    void refreshRotatesTheToken() {
        Session s = signIn();
        Res r = refresh(s.refresh());
        assertEquals(200, r.status(), r.raw());
        assertNotEquals(s.refresh(), r.body().get("refreshToken").asText());
        assertEquals(200, get("/api/auth/me", r.body().get("accessToken").asText()).status());
    }

    @Test
    void reusedRefreshTokenRevokesFamily() {
        Session s = signIn();
        Res rotated = refresh(s.refresh());
        assertEquals(200, rotated.status());
        String successor = rotated.body().get("refreshToken").asText();
        ageRotation(s.email());

        Res replay = refresh(s.refresh()); // the old token again, long after the benign window: theft
        assertEquals(401, replay.status(), replay.raw());
        assertEquals("urn:e9x:problem:invalid-refresh-token", replay.problemType());

        assertEquals(401, refresh(successor).status(), "the legitimate successor died with its family");
        assertEquals(0, su.queryForObject(
                "select count(*) from refresh_tokens where user_id = ? and revoked_at is null", Integer.class, userId(s.email())));
        // an access token already issued is stateless and lives until it expires; that is the documented trade-off
        assertEquals(200, get("/api/auth/me", rotated.body().get("accessToken").asText()).status());
    }

    @Test
    void anotherFamilyOfTheSameUserSurvivesAReuse() {
        Session a = signIn();
        Res b = login(a.email(), PASSWORD); // second device
        refresh(a.refresh());
        ageRotation(a.email());
        assertEquals(401, refresh(a.refresh()).status());
        assertEquals(200, refresh(b.body().get("refreshToken").asText()).status(), "only the compromised family is revoked");
    }

    @Test
    void twoTabsRefreshingTogetherAreNotLoggedOut() throws Exception {
        Session s = signIn();
        CyclicBarrier go = new CyclicBarrier(2);
        ExecutorService pool = Executors.newFixedThreadPool(2);
        try {
            Callable<Res> call = () -> {
                go.await();
                return refresh(s.refresh());
            };
            Future<Res> t1 = pool.submit(call);
            Future<Res> t2 = pool.submit(call);
            Res a = t1.get();
            Res b = t2.get();
            assertEquals(200, a.status(), a.raw());
            assertEquals(200, b.status(), b.raw());
            String ta = a.body().get("refreshToken").asText();
            String tb = b.body().get("refreshToken").asText();
            assertNotEquals(ta, tb);
            assertEquals(200, refresh(ta).status(), "whichever tab wrote its token last keeps a working session");
            assertEquals(200, refresh(tb).status());
        } finally {
            pool.shutdownNow();
        }
    }

    @Test
    void aSecondUseJustAfterRotationIsABenignRaceNotTheft() {
        Session s = signIn();
        assertEquals(200, refresh(s.refresh()).status());
        Res again = refresh(s.refresh()); // within the 10 s grace
        assertEquals(200, again.status(), again.raw());
        assertEquals(0, su.queryForObject("select count(*) from refresh_tokens where revoked_at is not null and user_id = ?",
                Integer.class, userId(s.email())), "nothing revoked");
    }

    @Test
    void anExpiredRefreshTokenIsRejected() {
        Session s = signIn();
        su.update("update refresh_tokens set expires_at = now() - interval '1 second' where user_id = ?", userId(s.email()));
        assertEquals(401, refresh(s.refresh()).status());
    }

    @Test
    void logoutRevokesTheWholeFamily() {
        Session s = signIn();
        Res r = refresh(s.refresh());
        String latest = r.body().get("refreshToken").asText();
        assertEquals(204, post("/api/auth/logout", Map.of("refreshToken", latest)).status());
        assertEquals(401, refresh(latest).status());
        assertEquals(401, refresh(s.refresh()).status());
        assertEquals(204, post("/api/auth/logout", Map.of("refreshToken", "A".repeat(43))).status(), "logout never tells whether a token existed");
    }

    @Test
    void garbageAnAccessTokenAndAnotherUsersTokenAreNotRefreshTokens() {
        Session s = signIn();
        List<String> bad = List.of("x", "A".repeat(43), s.access(), UUID.randomUUID().toString().replace("-", "") + "AAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAA");
        for (String t : bad) {
            int status = refresh(t).status();
            // an access token is longer than any refresh token (400 from validation); the rest are 401
            assertTrue(status == 401 || (t.equals(s.access()) && status == 400), t + " -> " + status);
        }
        assertTrue(refresh("").status() == 400);
    }

    @Test
    void theRefreshTokenOfAGoogleOnlyOrDeletedUserIsHandledWithoutAnError() {
        Session s = signIn();
        su.update("delete from users where id = ?", userId(s.email()));
        assertEquals(401, refresh(s.refresh()).status(), "cascade removed its tokens");
    }
}
