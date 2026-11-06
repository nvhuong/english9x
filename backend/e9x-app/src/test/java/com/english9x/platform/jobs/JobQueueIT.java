package com.english9x.platform.jobs;

import static com.english9x.platform.jobs.JobTestSupport.SECRET;
import static com.english9x.platform.jobs.JobTestSupport.T0;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.english9x.platform.jobs.JobTestSupport.Recorder;
import com.english9x.platform.jobs.api.JobQueue;
import com.english9x.support.MutableClock;
import com.english9x.support.TestPostgres;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.time.Duration;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.Callable;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.ApplicationContext;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.context.TestPropertySource;
import org.springframework.transaction.IllegalTransactionStateException;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * The job queue against a real PostgreSQL, with the production role split: requests enqueue as e9x_app_login, the
 * worker claims as e9x_worker_login. Time is a {@link MutableClock}; the background threads sit in their first
 * poll-interval (1h), so each test drives {@link JobWorker#pollOnce()} itself.
 */
@SpringBootTest(
        webEnvironment = SpringBootTest.WebEnvironment.NONE,
        properties = {
            "e9x.jobs.poll-interval=1h",
            "e9x.jobs.lease=10m",
            "e9x.jobs.test_flaky.retry-delays=10s,20s",
            "e9x.jobs.test_noretry.retry-delays=",
            "e9x.jobs.test_throws.retry-delays=1s",
            "e9x.jobs.test_long_failure.retry-delays=",
            "logging.level.com.english9x.platform.jobs=DEBUG"
        })
@org.springframework.context.annotation.Import(JobTestSupport.class)
@org.junit.jupiter.api.extension.ExtendWith(org.springframework.boot.test.system.OutputCaptureExtension.class)
class JobQueueIT {

    static final String DB = TestPostgres.createDatabase();

    @DynamicPropertySource
    static void database(DynamicPropertyRegistry registry) {
        TestPostgres.connect(registry, DB);
    }

    @Autowired ApplicationContext ctx;
    @Autowired JobQueue queue;
    @Autowired PlatformTransactionManager tm;
    @Autowired MutableClock clock;
    @Autowired Recorder recorder;
    @Autowired ObjectMapper mapper;

    /** Sees past RLS: fixtures and assertions only. */
    final JdbcTemplate su = new JdbcTemplate(TestPostgres.superuser(DB));

    JobWorker worker() {
        return ctx.getBean(JobWorker.class);
    }

    JobStore store() {
        return ctx.getBean(JobStore.class);
    }

    @BeforeEach
    void clean() {
        su.update("delete from jobs");
        recorder.reset();
        clock.set(T0);
    }

    UUID enqueue(String type, String payloadJson, Instant runAt) {
        try {
            JsonNode payload = mapper.readTree(payloadJson);
            return new TransactionTemplate(tm).execute(s -> queue.enqueue(type, payload, runAt));
        } catch (com.fasterxml.jackson.core.JsonProcessingException e) {
            throw new IllegalArgumentException(e);
        }
    }

    Map<String, Object> row(UUID id) {
        return su.queryForMap("select status, attempts, run_at, locked_until, last_error, finished_at from jobs where id = ?", id);
    }

    static Instant instant(Object timestamptz) {
        return ((java.sql.Timestamp) timestamptz).toInstant();
    }

    // ---------------------------------------------------------------- concurrency

    @Test
    void twoWorkersNeverTakeSameJob() throws Exception {
        int n = 300;
        new TransactionTemplate(tm).executeWithoutResult(s -> {
            for (int i = 0; i < n; i++) {
                queue.enqueue("test_ok", mapper.createObjectNode().put("n", i), T0);
            }
        });
        recorder.sleepMillis = 2;

        ExecutorService pool = Executors.newFixedThreadPool(2);
        CountDownLatch go = new CountDownLatch(1);
        List<Callable<Integer>> workers = new ArrayList<>();
        for (int w = 0; w < 2; w++) {
            workers.add(() -> {
                go.await();
                int done = 0;
                while (worker().pollOnce()) {
                    done++;
                }
                return done;
            });
        }
        List<Future<Integer>> futures = new ArrayList<>();
        for (Callable<Integer> c : workers) {
            futures.add(pool.submit(c));
        }
        go.countDown();
        int a = futures.get(0).get(60, TimeUnit.SECONDS);
        int b = futures.get(1).get(60, TimeUnit.SECONDS);
        pool.shutdownNow();

        assertEquals(n, a + b, "every job processed exactly once in total");
        assertTrue(a > 0 && b > 0, "both workers took part (" + a + " / " + b + ")");
        assertEquals(n, recorder.runs.size());
        recorder.runs.forEach((id, runs) -> assertEquals(1, runs.get(), "job " + id + " ran more than once"));
        assertEquals(n, su.queryForObject("select count(*) from jobs where status = 'SUCCEEDED' and attempts = 1", Integer.class));
    }

    // ---------------------------------------------------------------- retries

    @Test
    void retriesWithConfiguredDelays() {
        UUID id = enqueue("test_flaky", "{}", T0);

        assertTrue(worker().pollOnce());
        Map<String, Object> r = row(id);
        assertEquals("QUEUED", r.get("status"));
        assertEquals(1, r.get("attempts"));
        assertEquals(T0.plusSeconds(10), instant(r.get("run_at")), "first retry after 10s");
        assertEquals("boom", r.get("last_error"));
        assertNull(r.get("locked_until"));

        assertFalse(worker().pollOnce(), "not due yet");
        clock.advance(Duration.ofSeconds(9));
        assertFalse(worker().pollOnce(), "9s later still not due");
        clock.advance(Duration.ofSeconds(1));
        assertTrue(worker().pollOnce());
        r = row(id);
        assertEquals("QUEUED", r.get("status"));
        assertEquals(2, r.get("attempts"));
        assertEquals(T0.plusSeconds(10 + 20), instant(r.get("run_at")), "second retry 20s after the second attempt");
        assertEquals(List.of(1, 2), recorder.attempts);
    }

    @Test
    void marksFailedAfterLastRetry() {
        UUID id = enqueue("test_flaky", "{}", T0);
        assertTrue(worker().pollOnce());
        clock.advance(Duration.ofSeconds(10));
        assertTrue(worker().pollOnce());
        clock.advance(Duration.ofSeconds(20));
        assertTrue(worker().pollOnce(), "third and last attempt: the first plus two retries");

        Map<String, Object> r = row(id);
        assertEquals("FAILED", r.get("status"));
        assertEquals(3, r.get("attempts"));
        assertEquals("boom", r.get("last_error"));
        assertEquals(clock.instant(), instant(r.get("finished_at")));
        assertEquals(List.of(1, 2, 3), recorder.attempts);

        clock.advance(Duration.ofDays(1));
        assertFalse(worker().pollOnce(), "a FAILED job is never picked again");
        assertEquals(3, recorder.attempts.size());
    }

    @Test
    void emptyRetryDelaysMeanFailedAfterOneAttempt() {
        UUID id = enqueue("test_noretry", "{}", T0);
        assertTrue(worker().pollOnce());
        Map<String, Object> r = row(id);
        assertEquals("FAILED", r.get("status"));
        assertEquals(1, r.get("attempts"));
        assertEquals("once", r.get("last_error"));
    }

    @Test
    void aThrowingHandlerBehavesLikeAFailureResultAndLeaksNothing(org.springframework.boot.test.system.CapturedOutput output) {
        UUID id = enqueue("test_throws", "{\"secret\":\"" + SECRET + "\"}", T0);

        assertTrue(worker().pollOnce());
        Map<String, Object> r = row(id);
        assertEquals("QUEUED", r.get("status"), "retried, like a failure result");
        assertEquals(T0.plusSeconds(1), instant(r.get("run_at")));
        String error = (String) r.get("last_error");
        assertNotNull(error);
        assertTrue(error.contains("IllegalStateException"), error);
        assertFalse(error.contains(SECRET), "the exception message quotes the payload and must not be stored");

        clock.advance(Duration.ofSeconds(1));
        assertTrue(worker().pollOnce());
        r = row(id);
        assertEquals("FAILED", r.get("status"));
        assertEquals(2, r.get("attempts"));
        assertFalse(((String) r.get("last_error")).contains(SECRET));
        assertFalse(output.getAll().contains(SECRET), "payload contents must never reach the logs");
    }

    @Test
    void storedErrorTextIsLengthLimited() {
        UUID id = enqueue("test_long_failure", "{}", T0);
        assertTrue(worker().pollOnce());
        String error = (String) row(id).get("last_error");
        assertTrue(error.length() <= 500, "length " + error.length());
        assertTrue(error.startsWith("xxxx"));
    }

    @Test
    void anUnknownTypeIsFailedNotSpunOn() {
        UUID id = enqueue("nobody_handles_this", "{}", T0);
        assertTrue(worker().pollOnce());
        Map<String, Object> r = row(id);
        assertEquals("FAILED", r.get("status"));
        assertTrue(((String) r.get("last_error")).contains("no handler"), String.valueOf(r.get("last_error")));
        assertFalse(worker().pollOnce(), "nothing left to claim");
        assertEquals(1, row(id).get("attempts"));
    }

    // ---------------------------------------------------------------- leases

    @Test
    void aCrashedWorkersJobBecomesClaimableAfterTheLeaseAndDoesNotCountAsSuccess() {
        UUID id = enqueue("test_ok", "{}", T0);

        ClaimedJob lost = store().claim(clock.instant()).orElseThrow(); // the worker then "crashes": no result recorded
        Map<String, Object> r = row(id);
        assertEquals("RUNNING", r.get("status"));
        assertEquals(1, r.get("attempts"));
        assertEquals(T0.plus(Duration.ofMinutes(10)), instant(r.get("locked_until")));

        clock.set(T0.plus(Duration.ofMinutes(10)).minusSeconds(1));
        assertFalse(worker().pollOnce(), "still leased");
        assertTrue(recorder.runs.isEmpty());

        clock.set(T0.plus(Duration.ofMinutes(10)));
        assertTrue(worker().pollOnce(), "lease expired: claimable again");
        assertEquals(1, recorder.runs.get(id).get());
        r = row(id);
        assertEquals("SUCCEEDED", r.get("status"), "succeeded by the second run, not by the lost one");
        assertEquals(2, r.get("attempts"), "the lost run counted as an attempt");

        assertFalse(store().succeed(lost, clock.instant()), "the stale run's late result is fenced off");
        assertEquals("SUCCEEDED", row(id).get("status"));
    }

    @Test
    void aLateResultFromAnExpiredLeaseCannotOverwriteTheNewRun() {
        UUID id = enqueue("test_ok", "{}", T0);
        ClaimedJob first = store().claim(clock.instant()).orElseThrow();
        clock.advance(Duration.ofMinutes(11));
        ClaimedJob second = store().claim(clock.instant()).orElseThrow();
        assertEquals(id, second.job().id());
        assertEquals(2, second.job().attempt());

        assertFalse(store().succeed(first, clock.instant()));
        assertFalse(store().fail(first, "late", clock.instant()));
        assertFalse(store().retryAt(first, clock.instant(), "late", clock.instant()));
        assertEquals("RUNNING", row(id).get("status"), "the newer run still owns the job");

        assertTrue(store().succeed(second, clock.instant()));
        assertEquals("SUCCEEDED", row(id).get("status"));
    }

    @Test
    void aLeaseThatExpiresAfterTheLastAttemptFailsTheJobWithoutRunningIt() {
        UUID id = enqueue("test_noretry", "{}", T0); // one attempt only
        store().claim(clock.instant()).orElseThrow(); // crash
        clock.advance(Duration.ofMinutes(10));

        assertTrue(worker().pollOnce());
        Map<String, Object> r = row(id);
        assertEquals("FAILED", r.get("status"));
        assertTrue(((String) r.get("last_error")).contains("lease"), String.valueOf(r.get("last_error")));
        assertTrue(recorder.runs.isEmpty(), "a handler that may already have run is not started again past its budget");
        assertFalse(worker().pollOnce());
    }

    // ---------------------------------------------------------------- enqueue

    @Test
    void enqueueIsPartOfTheCallersTransaction() {
        assertThrows(IllegalStateException.class, () -> new TransactionTemplate(tm).executeWithoutResult(s -> {
            queue.enqueue("test_ok", mapper.createObjectNode(), T0);
            throw new IllegalStateException("caller fails after enqueueing");
        }));
        assertEquals(0, su.queryForObject("select count(*) from jobs", Integer.class), "rolled back with the caller");

        enqueue("test_ok", "{}", T0);
        assertEquals(1, su.queryForObject("select count(*) from jobs", Integer.class), "committed with the caller");
    }

    @Test
    void enqueueWithoutATransactionIsRefused() {
        assertThrows(IllegalTransactionStateException.class, () -> queue.enqueue("test_ok", mapper.createObjectNode(), T0));
        assertEquals(0, su.queryForObject("select count(*) from jobs", Integer.class));
    }

    @Test
    void enqueueDefaultsAndValidation() {
        UUID id = new TransactionTemplate(tm).execute(s -> queue.enqueue("test_ok", null, null));
        Map<String, Object> r = row(id);
        assertEquals("QUEUED", r.get("status"));
        assertEquals(0, r.get("attempts"));
        assertEquals("{}", su.queryForObject("select payload::text from jobs where id = ?", String.class, id));
        assertEquals(clock.instant(), instant(r.get("run_at")), "runAt defaults to now (the clock)");

        for (String bad : new String[] {null, "", "Upper", "has space", "1abc", "x".repeat(65)}) {
            assertThrows(IllegalArgumentException.class, () -> new TransactionTemplate(tm)
                    .executeWithoutResult(s -> queue.enqueue(bad, mapper.createObjectNode(), T0)), "type '" + bad + "'");
        }
    }

    @Test
    void aDueJobWaitsForItsRunAt() {
        UUID id = enqueue("test_ok", "{}", T0.plusSeconds(60));
        assertFalse(worker().pollOnce());
        clock.advance(Duration.ofSeconds(60));
        assertTrue(worker().pollOnce());
        assertEquals("SUCCEEDED", row(id).get("status"));
    }

    // ---------------------------------------------------------------- the user-context path for handlers

    @Test
    void aHandlerSeesAUsersRlsViewOnlyByBindingTheContextItself() {
        UUID user = UUID.randomUUID();
        UUID org = UUID.randomUUID();
        su.update("insert into users(id,email) values (?,?)", user, user + "@example.test");
        su.update("insert into organizations(id,name,type) values (?,'O','CENTER')", org);
        su.update("insert into org_memberships(org_id,user_id) values (?,?)", org, user);
        su.update("insert into classes(owner_org_id,name) values (?,'visible to the org')", org);

        enqueue("test_ctx", "{\"userId\":\"" + user + "\",\"orgId\":\"" + org + "\"}", T0);
        assertTrue(worker().pollOnce());

        assertEquals(List.of(1, 0), recorder.contextVisibleRows, "bound: the org's row; unbound: nothing");
        assertEquals("SUCCEEDED", su.queryForObject("select status from jobs", String.class));
        su.update("delete from org_memberships");
        su.update("delete from classes");
        su.update("delete from organizations");
        su.update("delete from users");
    }

    // ---------------------------------------------------------------- app.worker.enabled=false

    @Nested
    @TestPropertySource(properties = {"app.worker.enabled=false", "e9x.jobs.poll-interval=50ms"})
    class WorkerDisabled {

        // A @Nested test gets its own application context; the enclosing instance's fields belong to the enabled one.
        @Autowired ApplicationContext disabledCtx;
        @Autowired JobQueue disabledQueue;
        @Autowired PlatformTransactionManager disabledTm;
        @Autowired MutableClock disabledClock;
        @Autowired Recorder disabledRecorder;

        @Test
        void disabledWorkerDoesNotPoll() throws Exception {
            assertEquals(0, disabledCtx.getBeanNamesForType(JobWorker.class).length, "no worker bean");
            assertEquals(0, disabledCtx.getBeanNamesForType(WorkerDatabase.class).length, "no worker connection pool");
            assertEquals(0, disabledCtx.getBeanNamesForType(JobStore.class).length);

            disabledClock.set(T0.plusSeconds(5));
            UUID id = new TransactionTemplate(disabledTm).execute(s -> disabledQueue.enqueue("test_ok", mapper.createObjectNode(), T0));
            Thread.sleep(600); // twelve poll intervals if a poller existed

            Map<String, Object> r = row(id);
            assertEquals("QUEUED", r.get("status"));
            assertEquals(0, r.get("attempts"));
            assertTrue(disabledRecorder.runs.isEmpty());
        }
    }
}
