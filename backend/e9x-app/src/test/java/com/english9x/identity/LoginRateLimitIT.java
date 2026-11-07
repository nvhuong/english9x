package com.english9x.identity;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.Map;
import org.junit.jupiter.api.Test;
import org.springframework.test.context.TestPropertySource;

/** e9x.auth.login-rate-limit, counted per IP + email on failures. Configured to 3 per 15 minutes here. */
@TestPropertySource(properties = "e9x.auth.login-rate-limit=3/15m")
class LoginRateLimitIT extends IdentityIT {

    @Test
    void blocksAfterConfiguredAttempts() {
        String email = registerVerified(uniqueEmail(), PASSWORD);
        for (int i = 0; i < 3; i++) {
            assertEquals(401, login(email, "wrong-password-" + i).status(), "failure " + (i + 1) + " is a plain 401");
        }
        Res blocked = login(email, PASSWORD); // even the right password is refused once the limit is used up
        assertEquals(429, blocked.status(), blocked.raw());
        assertEquals("urn:e9x:problem:too-many-attempts", blocked.problemType());
        assertTrue(blocked.headers().getContentType().toString().contains("problem+json"));
        assertNotNull(blocked.headers().getFirst("Retry-After"));
        assertTrue(Long.parseLong(blocked.headers().getFirst("Retry-After")) > 0);
    }

    @Test
    void otherAccountsAreNotAffected() {
        String victim = registerVerified(uniqueEmail(), PASSWORD);
        String other = registerVerified(uniqueEmail(), PASSWORD);
        for (int i = 0; i < 3; i++) {
            login(victim, "nope-" + i);
        }
        assertEquals(429, login(victim, PASSWORD).status());
        assertEquals(200, login(other, PASSWORD).status(), "the limit is per IP + email");
    }

    @Test
    void anUnknownEmailIsLimitedAndAnsweredExactlyLikeAKnownOne() {
        String known = registerVerified(uniqueEmail(), PASSWORD);
        String unknown = uniqueEmail();
        Res k = null;
        Res u = null;
        for (int i = 0; i < 4; i++) {
            k = login(known, "wrong-" + i);
            u = login(unknown, "wrong-" + i);
            assertEquals(k.status(), u.status(), "attempt " + i);
        }
        assertEquals(429, k.status());
        assertEquals(k.body().path("type"), u.body().path("type"));
        assertEquals(k.body().path("detail"), u.body().path("detail"));
    }

    @Test
    void aSuccessfulLoginResetsTheCount() {
        String email = registerVerified(uniqueEmail(), PASSWORD);
        login(email, "x1");
        login(email, "x2");
        assertEquals(200, login(email, PASSWORD).status());
        login(email, "x3");
        login(email, "x4");
        assertEquals(200, login(email, PASSWORD).status(), "2 + 2 failures with a success between never reach 3");
    }

    @Test
    void aClientSentForwardedForCannotBypassTheLimit() {
        String email = registerVerified(uniqueEmail(), PASSWORD);
        for (int i = 0; i < 3; i++) {
            Res r = post("/api/auth/login", Map.of("email", email, "password", "wrong-" + i), Map.of("X-Forwarded-For", "10.9." + i + ".1"));
            assertEquals(401, r.status());
        }
        Res fresh = post("/api/auth/login", Map.of("email", email, "password", PASSWORD), Map.of("X-Forwarded-For", "203.0.113.77"));
        assertEquals(429, fresh.status(), "no proxy is trusted, so the header is ignored and the real peer is counted");
    }

    /** The attempt is reserved before the (slow) password check: parallel guesses cannot all slip through it. */
    @Test
    void parallelWrongPasswordsGetExactlyTheLimitEvaluated() throws Exception {
        for (int round = 0; round < 5; round++) {
            String email = registerVerified(uniqueEmail(), PASSWORD);
            int threads = 40;
            java.util.concurrent.CyclicBarrier go = new java.util.concurrent.CyclicBarrier(threads);
            java.util.concurrent.ExecutorService pool = java.util.concurrent.Executors.newFixedThreadPool(threads);
            try {
                java.util.List<java.util.concurrent.Future<Integer>> results = new java.util.ArrayList<>();
                for (int i = 0; i < threads; i++) {
                    int n = i;
                    results.add(pool.submit(() -> {
                        go.await();
                        return login(email, "wrong-password-" + n).status();
                    }));
                }
                java.util.Map<Integer, Integer> statuses = new java.util.TreeMap<>();
                for (java.util.concurrent.Future<Integer> f : results) {
                    statuses.merge(f.get(), 1, Integer::sum);
                }
                assertEquals(java.util.Map.of(401, 3, 429, threads - 3), statuses, "round " + round + ": limit 3/15m, statuses " + statuses);
            } finally {
                pool.shutdownNow();
            }
        }
    }
}
