package com.english9x.identity;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.english9x.support.FakeOidcProvider;
import com.english9x.support.SmtpSink;
import com.english9x.support.TestAuth;
import com.english9x.support.TestPostgres;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.UUID;
import java.util.concurrent.Callable;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CyclicBarrier;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicBoolean;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

/**
 * A credential change must stop every session, including what is being minted at that very moment. Each race runs
 * several rounds. Two kinds of test:
 * <ul>
 *   <li>"Held" tests are deterministic: a rotation is left uncommitted on its own connection, the competing operation
 *       starts over HTTP, then the rotation commits. Whatever the scheduler does, that is the interleaving under test.
 *   <li>Login races use real threads started on a barrier that keep signing in while the credentials change, with
 *       a bcrypt cost high enough that many logins are between "password checked" and "token issued" at that moment.
 * </ul>
 */
class IdentityRaceIT extends IdentityIT {

    private static final int ROUNDS = 5;
    private static final FakeOidcProvider IDP = new FakeOidcProvider();

    @DynamicPropertySource
    static void settings(DynamicPropertyRegistry registry) {
        registry.add("e9x.auth.bcrypt-cost", () -> "8"); // keeps a login inside bcrypt long enough to overlap the change
        registry.add("e9x.auth.login-rate-limit", () -> "1000000/15m"); // the guards are under test here, not the limiter
        registry.add("e9x.auth.google.client-id", () -> FakeOidcProvider.CLIENT_ID);
        registry.add("e9x.auth.google.client-secret", () -> FakeOidcProvider.CLIENT_SECRET);
        registry.add("e9x.auth.google.authorization-uri", () -> IDP.base() + "/authorize");
        registry.add("e9x.auth.google.token-uri", () -> IDP.base() + "/token");
        registry.add("e9x.auth.google.jwk-set-uri", () -> IDP.base() + "/jwks");
        registry.add("e9x.auth.google.issuer", IDP::issuer);
    }

    @LocalServerPort
    int port;

    private final ExecutorService pool = Executors.newCachedThreadPool();

    // ---- helpers ------------------------------------------------------------------------------------------------

    private static byte[] digest(String raw) throws Exception {
        return MessageDigest.getInstance("SHA-256").digest(raw.getBytes(StandardCharsets.UTF_8));
    }

    private static String newRawToken() {
        return (UUID.randomUUID() + "-" + UUID.randomUUID()).replace("-", "");
    }

    /** A rotation done and NOT committed, on its own connection, as the runtime login. */
    private final class HeldRotation implements AutoCloseable {
        final Connection c = TestPostgres.app(DB).getConnection();
        final String successor = newRawToken();
        final String outcome;

        HeldRotation(String token) throws Exception {
            c.setAutoCommit(false);
            try (PreparedStatement p = c.prepareStatement(
                    "select r_outcome from auth_refresh_rotate(?, ?, now() + interval '30 days', now(), interval '10 seconds')")) {
                p.setBytes(1, digest(token));
                p.setBytes(2, digest(successor));
                try (ResultSet r = p.executeQuery()) {
                    r.next();
                    outcome = r.getString(1);
                }
            }
        }

        void commit() throws Exception {
            c.commit();
        }

        @Override
        public void close() throws Exception {
            c.close();
        }
    }

    /** Runs the competitor while the rotation is uncommitted; true when it finished without waiting for that commit. */
    private <T> Future<T> startCompetitor(Callable<T> competitor) {
        return pool.submit(competitor);
    }

    private static boolean finishedWithoutWaiting(Future<?> f) throws Exception {
        try {
            f.get(400, TimeUnit.MILLISECONDS);
            return true;
        } catch (TimeoutException e) {
            return false;
        }
    }

    private String refreshTokenOf(String email, String password) {
        Res r = login(email, password);
        assertEquals(200, r.status(), r.raw());
        return r.body().get("refreshToken").asText();
    }

    private String resetTokenFor(String email) {
        int before = smtp().to(email).size();
        assertEquals(202, post("/api/auth/password-reset/request", Map.of("email", email)).status());
        SmtpSink.Mail mail = smtp().await(email, before);
        return mail.linkParam("token");
    }

    private int liveTokens(String email) {
        return su.queryForObject("select count(*) from refresh_tokens where user_id = ? and revoked_at is null", Integer.class, userId(email));
    }

    private int stillRefreshable(List<String> tokens) {
        int live = 0;
        for (String t : tokens) {
            if (post("/api/auth/refresh", Map.of("refreshToken", t)).status() == 200) {
                live++;
            }
        }
        return live;
    }

    /** Signs in on N threads until {@code change} has returned plus a moment; collects every refresh token handed out. */
    private List<String> keepSigningInDuring(String email, String password, Callable<Integer> change, int expectedStatus) throws Exception {
        List<String> tokens = new CopyOnWriteArrayList<>();
        AtomicBoolean stop = new AtomicBoolean();
        int threads = 6;
        CyclicBarrier go = new CyclicBarrier(threads + 1);
        List<Future<?>> workers = new ArrayList<>();
        for (int i = 0; i < threads; i++) {
            workers.add(pool.submit(() -> {
                go.await();
                while (!stop.get()) {
                    Res r = login(email, password);
                    if (r.status() == 200) {
                        tokens.add(r.body().get("refreshToken").asText());
                    }
                }
                return null;
            }));
        }
        go.await();
        while (tokens.size() < 6) { // the logins are really running
            Thread.sleep(5);
        }
        assertEquals(expectedStatus, change.call(), "the credential change itself");
        stop.set(true);
        for (Future<?> w : workers) {
            w.get(30, TimeUnit.SECONDS); // every login that was in flight has now finished
        }
        return tokens;
    }

    // ---- the five races of the review -----------------------------------------------------------------------------

    @Test
    void loginDuringResetLeavesNoLiveRefreshToken() throws Exception {
        for (int round = 0; round < ROUNDS; round++) {
            String email = registerVerified(uniqueEmail(), PASSWORD);
            String resetToken = resetTokenFor(email);
            List<String> tokens = keepSigningInDuring(email, PASSWORD, () -> post("/api/auth/password-reset/confirm",
                    Map.of("token", resetToken, "newPassword", "Brand-new-pass-2")).status(), 204);
            int live = stillRefreshable(tokens);
            assertEquals(0, live, "round " + round + ": of " + tokens.size() + " tokens issued for the old password, " + live + " survived the reset");
            assertEquals(401, login(email, PASSWORD).status(), "the old password is dead");
        }
    }

    @Test
    void rotationDuringResetCannotKeepTheSessionAlive() throws Exception {
        for (int round = 0; round < ROUNDS; round++) {
            String email = registerVerified(uniqueEmail(), PASSWORD);
            String token = refreshTokenOf(email, PASSWORD);
            String resetToken = resetTokenFor(email);
            boolean waited;
            String successor;
            try (HeldRotation rotation = new HeldRotation(token)) {
                assertEquals("ROTATED", rotation.outcome);
                successor = rotation.successor;
                Future<Res> reset = startCompetitor(() -> post("/api/auth/password-reset/confirm",
                        Map.of("token", resetToken, "newPassword", "Brand-new-pass-2")));
                waited = !finishedWithoutWaiting(reset);
                rotation.commit();
                assertEquals(204, reset.get(30, TimeUnit.SECONDS).status());
            }
            assertEquals(0, liveTokens(email), "round " + round + ": the successor minted during the reset is revoked");
            assertEquals(401, post("/api/auth/refresh", Map.of("refreshToken", successor)).status(), "round " + round);
            assertTrue(waited, "the reset waits for the rotation in progress");
        }
    }

    @Test
    void rotationDuringReuseRevocationCannotEscapeTheFamily() throws Exception {
        for (int round = 0; round < ROUNDS; round++) {
            String email = registerVerified(uniqueEmail(), PASSWORD);
            String stolen = refreshTokenOf(email, PASSWORD);
            Res first = post("/api/auth/refresh", Map.of("refreshToken", stolen));
            assertEquals(200, first.status());
            String current = first.body().get("refreshToken").asText();
            su.update("update refresh_tokens set rotated_at = now() - interval '5 minutes' where rotated_at is not null and user_id = ?", userId(email));
            boolean waited;
            String successor;
            try (HeldRotation rotation = new HeldRotation(current)) {
                assertEquals("ROTATED", rotation.outcome);
                successor = rotation.successor;
                Future<Res> replay = startCompetitor(() -> post("/api/auth/refresh", Map.of("refreshToken", stolen)));
                waited = !finishedWithoutWaiting(replay);
                rotation.commit();
                assertEquals(401, replay.get(30, TimeUnit.SECONDS).status());
            }
            assertEquals(0, liveTokens(email), "round " + round + ": the token minted while the family was being revoked is revoked too");
            assertEquals(401, post("/api/auth/refresh", Map.of("refreshToken", successor)).status(), "round " + round);
            assertTrue(waited, "the revocation waits for the rotation in progress");
        }
    }

    @Test
    void logoutDuringRotationRevokesTheNewToken() throws Exception {
        for (int round = 0; round < ROUNDS; round++) {
            String email = registerVerified(uniqueEmail(), PASSWORD);
            String token = refreshTokenOf(email, PASSWORD);
            boolean waited;
            String successor;
            try (HeldRotation rotation = new HeldRotation(token)) {
                successor = rotation.successor;
                Future<Res> logout = startCompetitor(() -> post("/api/auth/logout", Map.of("refreshToken", token)));
                waited = !finishedWithoutWaiting(logout);
                rotation.commit();
                assertEquals(204, logout.get(30, TimeUnit.SECONDS).status());
            }
            assertEquals(0, liveTokens(email), "round " + round + ": logout also caught the token minted while it ran");
            assertEquals(401, post("/api/auth/refresh", Map.of("refreshToken", successor)).status(), "round " + round);
            assertTrue(waited, "the logout waits for the rotation in progress");
        }
    }

    @Test
    void loginDuringNeutralisationLeavesTheAttackerNoSession() throws Exception {
        for (int round = 0; round < ROUNDS; round++) {
            String victim = uniqueEmail();
            registerUnverified(victim, "Attacker-password-1");
            List<String> tokens = keepSigningInDuring(victim, "Attacker-password-1", () -> {
                FakeOidcProvider.Scenario s = new FakeOidcProvider.Scenario();
                s.email = victim;
                s.emailVerified = true;
                return googleCallbackStatus(s);
            }, 302);
            int live = stillRefreshable(tokens);
            assertEquals(0, live, "round " + round + ": of " + tokens.size() + " attacker sessions, " + live + " survived the neutralisation");
            assertEquals(401, login(victim, "Attacker-password-1").status());
        }
    }

    private int googleCallbackStatus(FakeOidcProvider.Scenario s) throws Exception {
        HttpClient client = HttpClient.newBuilder().followRedirects(HttpClient.Redirect.NEVER).build();
        IDP.willSignIn(s);
        HttpResponse<String> start = client.send(HttpRequest.newBuilder(URI.create("http://localhost:" + port + "/api/auth/oauth2/google")).GET().build(),
                HttpResponse.BodyHandlers.ofString());
        String cookie = start.headers().allValues("Set-Cookie").get(0).split(";")[0];
        HttpResponse<String> authorize = client.send(HttpRequest.newBuilder(URI.create(start.headers().firstValue("Location").orElseThrow())).GET().build(),
                HttpResponse.BodyHandlers.ofString());
        String back = authorize.headers().firstValue("Location").orElseThrow();
        String local = "http://localhost:" + port + back.substring(TestAuth.BASE_URL.length());
        HttpResponse<String> callback = client.send(HttpRequest.newBuilder(URI.create(local)).header("Cookie", cookie).GET().build(),
                HttpResponse.BodyHandlers.ofString());
        assertTrue(callback.headers().firstValue("Location").orElse("").contains("/login/google"), "the callback succeeded: " + callback.headers().map());
        return callback.statusCode();
    }

    // ---- no deadlock path --------------------------------------------------------------------------------------

    private long deadlocks() {
        return su.queryForObject("select deadlocks from pg_stat_database where datname = current_database()", Long.class);
    }

    /** Two replays of one stale token at once: both must take the exclusive path without an upgrade deadlock. */
    @Test
    void concurrentReplaysOfAStaleTokenDoNotDeadlock() throws Exception {
        long before = deadlocks();
        for (int round = 0; round < ROUNDS; round++) {
            String email = registerVerified(uniqueEmail(), PASSWORD);
            String stale = refreshTokenOf(email, PASSWORD);
            String current = post("/api/auth/refresh", Map.of("refreshToken", stale)).body().get("refreshToken").asText();
            su.update("update refresh_tokens set rotated_at = now() - interval '5 minutes' where rotated_at is not null and user_id = ?", userId(email));
            int n = 8;
            CyclicBarrier go = new CyclicBarrier(n);
            List<Future<Integer>> all = new ArrayList<>();
            for (int i = 0; i < n; i++) {
                all.add(pool.submit(() -> {
                    go.await();
                    return post("/api/auth/refresh", Map.of("refreshToken", stale)).status();
                }));
            }
            for (Future<Integer> f : all) {
                assertEquals(401, f.get(30, TimeUnit.SECONDS), "round " + round);
            }
            assertEquals(401, post("/api/auth/refresh", Map.of("refreshToken", current)).status());
            assertEquals(0, liveTokens(email));
        }
        Thread.sleep(1_500); // deadlock counters are flushed at most once a second
        assertEquals(before, deadlocks());
    }

    /** Every operation on one account, at once. A deadlock would surface as a 500 (and in the statistics). */
    @Test
    void everythingAtOnceHasNoDeadlockAndNoServerError() throws Exception {
        long before = deadlocks();
        Map<Integer, Integer> seen = new TreeMap<>();
        for (int round = 0; round < ROUNDS * 2; round++) {
            String email = registerVerified(uniqueEmail(), PASSWORD);
            String a = refreshTokenOf(email, PASSWORD);
            String b = refreshTokenOf(email, PASSWORD);
            String c = refreshTokenOf(email, PASSWORD);
            String stale = refreshTokenOf(email, PASSWORD);
            post("/api/auth/refresh", Map.of("refreshToken", stale));
            su.update("update refresh_tokens set rotated_at = now() - interval '5 minutes' where rotated_at is not null and user_id = ?", userId(email));
            String resetToken = resetTokenFor(email);
            List<Callable<Integer>> work = List.of(
                    () -> post("/api/auth/refresh", Map.of("refreshToken", a)).status(),
                    () -> post("/api/auth/refresh", Map.of("refreshToken", a)).status(),
                    () -> post("/api/auth/refresh", Map.of("refreshToken", b)).status(),
                    () -> post("/api/auth/refresh", Map.of("refreshToken", stale)).status(),
                    () -> post("/api/auth/refresh", Map.of("refreshToken", stale)).status(),
                    () -> post("/api/auth/logout", Map.of("refreshToken", b)).status(),
                    () -> post("/api/auth/logout", Map.of("refreshToken", c)).status(),
                    () -> login(email, PASSWORD).status(),
                    () -> post("/api/auth/password-reset/request", Map.of("email", email)).status(),
                    () -> post("/api/auth/verify-email/resend", Map.of("email", email)).status(),
                    () -> post("/api/auth/password-reset/confirm", Map.of("token", resetToken, "newPassword", "Brand-new-pass-2")).status());
            CyclicBarrier go = new CyclicBarrier(work.size());
            List<Future<Integer>> all = new ArrayList<>();
            for (Callable<Integer> w : work) {
                all.add(pool.submit(() -> {
                    go.await();
                    return w.call();
                }));
            }
            for (Future<Integer> f : all) {
                int status = f.get(60, TimeUnit.SECONDS);
                seen.merge(status, 1, Integer::sum);
                assertTrue(status < 500, "round " + round + " answered " + status + "; so far " + seen);
            }
        }
        Thread.sleep(1_500);
        assertEquals(before, deadlocks());
        assertFalse(seen.isEmpty());
    }
}
