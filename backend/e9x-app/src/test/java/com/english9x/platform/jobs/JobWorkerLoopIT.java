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
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.NONE, properties = {"e9x.jobs.poll-interval=100ms", "e9x.jobs.workers=2"})
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

        assertEquals(2, worker.liveThreads());
        worker.stop();
        assertTrue(!worker.isRunning());
        assertEquals(0, worker.liveThreads(), "stop() ends the worker threads");
        worker.start();
        assertTrue(worker.isRunning(), "and it can be started again");
        assertEquals(2, worker.liveThreads());
    }
}
