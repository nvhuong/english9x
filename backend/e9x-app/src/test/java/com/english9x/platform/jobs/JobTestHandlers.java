package com.english9x.platform.jobs;

import com.english9x.org.api.CurrentContextHolder;
import com.english9x.platform.jobs.JobTestSupport.Recorder;
import com.english9x.platform.jobs.api.Job;
import com.english9x.platform.jobs.api.JobExecutor;
import com.english9x.platform.jobs.api.JobHandler;
import com.english9x.platform.jobs.api.JobResult;
import java.util.UUID;
import org.springframework.boot.test.context.TestComponent;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * Test-only job handlers. They live outside the @TestConfiguration that defines them as beans (a @Configuration would
 * also register its nested @Component classes), and are @TestComponent so component scanning of other tests skips them.
 */
final class JobTestHandlers {

    private JobTestHandlers() {}

    @TestComponent
    @JobHandler("test_ok")
    record OkHandler(Recorder recorder) implements JobExecutor {
        @Override
        public JobResult handle(Job job) throws Exception {
            recorder.record(job);
            if (recorder.sleepMillis > 0) {
                Thread.sleep(recorder.sleepMillis);
            }
            return JobResult.success();
        }
    }

    /** Always returns a failure result; retry delays 10s,20s are set by the tests' properties. */
    @TestComponent
    @JobHandler("test_flaky")
    record FlakyHandler(Recorder recorder) implements JobExecutor {
        @Override
        public JobResult handle(Job job) {
            recorder.record(job);
            return JobResult.failure("boom");
        }
    }

    /** Retry delays are empty by the tests' properties: one attempt only. */
    @TestComponent
    @JobHandler("test_noretry")
    record NoRetryHandler(Recorder recorder) implements JobExecutor {
        @Override
        public JobResult handle(Job job) {
            recorder.record(job);
            return JobResult.failure("once");
        }
    }

    /** Throws with a message that quotes the payload, as a parser error would. */
    @TestComponent
    @JobHandler("test_throws")
    record ThrowingHandler(Recorder recorder) implements JobExecutor {
        @Override
        public JobResult handle(Job job) {
            recorder.record(job);
            throw new IllegalStateException("cannot parse " + job.payload().path("secret").asText());
        }
    }

    @TestComponent
    @JobHandler("test_long_failure")
    record LongFailureHandler() implements JobExecutor {
        @Override
        public JobResult handle(Job job) {
            return JobResult.failure("x".repeat(5000));
        }
    }

    /**
     * The documented path for a handler that needs a user's RLS view: bind the ids it wrote into its payload, then
     * run application transactions. Counts the rows of `classes` the user can see.
     */
    @TestComponent
    @JobHandler("test_ctx")
    record ContextHandler(Recorder recorder, JdbcTemplate app, PlatformTransactionManager tm) implements JobExecutor {
        @Override
        public JobResult handle(Job job) {
            UUID user = UUID.fromString(job.payload().path("userId").asText());
            UUID org = UUID.fromString(job.payload().path("orgId").asText());
            try (var ignored = CurrentContextHolder.bind(user, org)) {
                Integer n = new TransactionTemplate(tm)
                        .execute(s -> app.queryForObject("select count(*) from classes", Integer.class));
                recorder.contextVisibleRows.add(n);
            }
            Integer unbound = new TransactionTemplate(tm)
                    .execute(s -> app.queryForObject("select count(*) from classes", Integer.class));
            recorder.contextVisibleRows.add(unbound);
            return JobResult.success();
        }
    }

    @TestComponent
    @JobHandler("test_exhaust")
    record ExhaustHandler(Recorder recorder, com.english9x.platform.jobs.api.WorkerJdbc worker) implements JobExecutor {
        @Override
        public JobResult handle(Job job) {
            return JobResult.failure("nope");
        }

        @Override
        public void onExhausted(Job job, String reason) {
            recorder.exhausted.add(reason);
            recorder.statusAtExhaustion.add(worker.jdbc().queryForObject("select status from jobs where id = ?", String.class, job.id()));
        }
    }

    @TestComponent
    @JobHandler("test_exhaust_throws")
    record ExhaustThrowsHandler(Recorder recorder) implements JobExecutor {
        @Override
        public JobResult handle(Job job) {
            return JobResult.failure("nope");
        }

        @Override
        public void onExhausted(Job job, String reason) {
            recorder.exhausted.add(reason);
            throw new IllegalStateException("callback failed " + JobTestSupport.SECRET);
        }
    }

    @TestComponent
    @JobHandler("test_soe")
    record StackOverflowHandler() implements JobExecutor {
        @Override
        public JobResult handle(Job job) {
            return recurse(0);
        }

        private static JobResult recurse(int n) {
            return recurse(n + 1);
        }
    }

    /** Returns with the thread's interrupt flag still set. */
    @TestComponent
    @JobHandler("test_interrupt")
    record InterruptHandler() implements JobExecutor {
        @Override
        public JobResult handle(Job job) {
            Thread.currentThread().interrupt();
            return JobResult.success();
        }
    }

    /** Blocks until the test opens the recorder's gate. */
    @TestComponent
    @JobHandler("test_block")
    record BlockHandler(Recorder recorder) implements JobExecutor {
        @Override
        public JobResult handle(Job job) throws Exception {
            recorder.started.incrementAndGet();
            recorder.gate.await();
            return JobResult.success();
        }
    }

    @TestComponent
    @JobHandler("test_prio")
    record PrioHandler() implements JobExecutor {
        @Override
        public JobResult handle(Job job) {
            return JobResult.success();
        }
    }

    @TestComponent
    @JobHandler("test_workerjdbc")
    record WorkerJdbcHandler(Recorder recorder, com.english9x.platform.jobs.api.WorkerJdbc worker) implements JobExecutor {
        @Override
        public JobResult handle(Job job) {
            recorder.workerSeen.add(worker.jdbc().queryForObject("select count(*) from jobs", Integer.class));
            return JobResult.success();
        }
    }

    /** Binds a user context and never closes the scope. */
    @TestComponent
    @JobHandler("test_leak")
    record LeakHandler() implements JobExecutor {
        @Override
        public JobResult handle(Job job) {
            CurrentContextHolder.bind(UUID.randomUUID(), UUID.randomUUID());
            return JobResult.success();
        }
    }

    @TestComponent
    @JobHandler("test_probe")
    record ProbeHandler(Recorder recorder) implements JobExecutor {
        @Override
        public JobResult handle(Job job) {
            recorder.bindingSeen.add(CurrentContextHolder.current().isPresent());
            return JobResult.success();
        }
    }
}
