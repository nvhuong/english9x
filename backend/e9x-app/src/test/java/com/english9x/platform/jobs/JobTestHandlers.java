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
}
