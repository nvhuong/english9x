package com.english9x.platform.jobs;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.english9x.platform.jobs.JobTestSupport.Recorder;
import com.english9x.platform.jobs.api.JobQueue;
import com.english9x.support.MutableClock;
import com.english9x.support.TestPostgres;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.time.Instant;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

/** The real polling threads (no manual pollOnce): a job enqueued by a committed transaction gets run, and shutdown is clean. */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.NONE, properties = {"e9x.jobs.poll-interval=100ms", "e9x.jobs.workers=2", "e9x.jobs.priority-types=test_prio", "e9x.jobs.test_soe.retry-delays="})
@org.springframework.context.annotation.Import(JobTestSupport.class)
class JobWorkerLoopIT {

    static final String DB = TestPostgres.createDatabase();

    @DynamicPropertySource
    static void database(DynamicPropertyRegistry registry) {
        TestPostgres.connect(registry, DB);
    }

    @Autowired JobQueue queue;
    @Autowired PlatformTransactionManager tm;
    @Autowired MutableClock clock;
    @Autowired Recorder recorder;
    @Autowired ObjectMapper mapper;
    @Autowired JobWorker worker;

    final JdbcTemplate su = new JdbcTemplate(TestPostgres.superuser(DB));

    @Test
    void theWorkerThreadsRunACommittedJobAndStopOnShutdown() throws Exception {
        clock.set(Instant.now());
        assertTrue(worker.isRunning());
        UUID id = new TransactionTemplate(tm).execute(s -> queue.enqueue("test_ok", mapper.createObjectNode(), null));

        long deadline = System.currentTimeMillis() + 15_000;
        String status;
        do {
            status = su.queryForObject("select status from jobs where id = ?", String.class, id);
            if ("SUCCEEDED".equals(status)) {
                break;
            }
            Thread.sleep(50);
        } while (System.currentTimeMillis() < deadline);
        assertEquals("SUCCEEDED", status);
        assertEquals(1, recorder.runs.get(id).get());

        assertEquals(3, worker.liveThreads(), "2 general + 1 priority thread");
        worker.stop();
        assertTrue(!worker.isRunning());
        assertEquals(0, worker.liveThreads(), "stop() ends the worker threads");
        worker.start();
        assertTrue(worker.isRunning(), "and it can be started again");
        assertEquals(3, worker.liveThreads());
    }

    private void awaitStatus(UUID id, String status) throws Exception {
        long end = System.currentTimeMillis() + 15_000;
        while (!status.equals(su.queryForObject("select status from jobs where id = ?", String.class, id))) {
            if (System.currentTimeMillis() > end) {
                throw new AssertionError("job " + id + " never reached " + status);
            }
            Thread.sleep(30);
        }
    }

    @Test
    void aPriorityJobRunsWhileEveryGeneralThreadIsBusy() throws Exception {
        recorder.reset();
        clock.set(Instant.now());
        new TransactionTemplate(tm).executeWithoutResult(s -> {
            queue.enqueue("test_block", mapper.createObjectNode(), null);
            queue.enqueue("test_block", mapper.createObjectNode(), null);
        });
        long end = System.currentTimeMillis() + 15_000;
        while (recorder.started.get() < 2 && System.currentTimeMillis() < end) {
            Thread.sleep(20);
        }
        assertEquals(2, recorder.started.get(), "both general threads are now blocked");
        UUID prio = new TransactionTemplate(tm).execute(s -> queue.enqueue("test_prio", mapper.createObjectNode(), null));
        awaitStatus(prio, "SUCCEEDED");
        recorder.gate.countDown();
    }

    @Test
    void aLeftoverInterruptFlagAndAThrownErrorDoNotStopTheWorkers() throws Exception {
        recorder.reset();
        clock.set(Instant.now());
        UUID a = new TransactionTemplate(tm).execute(s -> queue.enqueue("test_interrupt", mapper.createObjectNode(), null));
        UUID soe = new TransactionTemplate(tm).execute(s -> queue.enqueue("test_soe", mapper.createObjectNode(), null));
        awaitStatus(a, "SUCCEEDED");
        awaitStatus(soe, "FAILED");
        UUID b = new TransactionTemplate(tm).execute(s -> queue.enqueue("test_ok", mapper.createObjectNode(), null));
        awaitStatus(b, "SUCCEEDED");
        assertTrue(worker.isRunning());
        assertEquals(3, worker.liveThreads());
        assertEquals(org.springframework.boot.actuate.health.Status.UP, health.health().getStatus());
    }

    @Autowired JobWorkerHealthIndicator health;
}
