package com.english9x.platform.jobs;

import com.english9x.org.api.CurrentContextHolder;
import com.english9x.platform.jobs.api.Job;
import com.english9x.platform.jobs.api.JobExecutor;
import com.english9x.platform.jobs.api.JobHandler;
import com.english9x.platform.jobs.api.JobResult;
import com.english9x.support.MutableClock;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicInteger;
import org.springframework.boot.test.context.TestComponent;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Primary;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

/** Handlers and clock shared by the job integration tests. Test sources only: no business handler ships with P1.7. */
@TestConfiguration
class JobTestSupport {

    static final Instant T0 = Instant.parse("2026-11-07T00:00:00Z");
    static final String SECRET = "SECRET-PAYLOAD-VALUE-4711";

    static final class Recorder {
        final Map<UUID, AtomicInteger> runs = new ConcurrentHashMap<>();
        final List<Integer> attempts = new CopyOnWriteArrayList<>();
        final List<Integer> contextVisibleRows = new CopyOnWriteArrayList<>();
        volatile long sleepMillis;
        final List<String> exhausted = new CopyOnWriteArrayList<>();
        final List<String> statusAtExhaustion = new CopyOnWriteArrayList<>();
        final List<Boolean> bindingSeen = new CopyOnWriteArrayList<>();
        final List<Integer> workerSeen = new CopyOnWriteArrayList<>();
        final AtomicInteger started = new AtomicInteger();
        volatile java.util.concurrent.CountDownLatch gate = new java.util.concurrent.CountDownLatch(1);

        void reset() {
            runs.clear();
            attempts.clear();
            contextVisibleRows.clear();
            sleepMillis = 0;
            exhausted.clear();
            statusAtExhaustion.clear();
            bindingSeen.clear();
            workerSeen.clear();
            started.set(0);
            gate.countDown();
            gate = new java.util.concurrent.CountDownLatch(1);
        }

        void record(Job job) {
            runs.computeIfAbsent(job.id(), k -> new AtomicInteger()).incrementAndGet();
            attempts.add(job.attempt());
        }
    }

    @Bean
    @Primary
    MutableClock testClock() {
        return new MutableClock(T0);
    }

    @Bean
    Recorder recorder() {
        return new Recorder();
    }

    @Bean
    JobTestHandlers.OkHandler okHandler(Recorder r) {
        return new JobTestHandlers.OkHandler(r);
    }

    @Bean
    JobTestHandlers.FlakyHandler flakyHandler(Recorder r) {
        return new JobTestHandlers.FlakyHandler(r);
    }

    @Bean
    JobTestHandlers.NoRetryHandler noRetryHandler(Recorder r) {
        return new JobTestHandlers.NoRetryHandler(r);
    }

    @Bean
    JobTestHandlers.ThrowingHandler throwingHandler(Recorder r) {
        return new JobTestHandlers.ThrowingHandler(r);
    }

    @Bean
    JobTestHandlers.LongFailureHandler longFailureHandler() {
        return new JobTestHandlers.LongFailureHandler();
    }

    @Bean
    JobTestHandlers.ExhaustHandler exhaustHandler(Recorder r, com.english9x.platform.jobs.api.WorkerJdbc w) {
        return new JobTestHandlers.ExhaustHandler(r, w);
    }

    @Bean
    JobTestHandlers.ExhaustThrowsHandler exhaustThrowsHandler(Recorder r) {
        return new JobTestHandlers.ExhaustThrowsHandler(r);
    }

    @Bean
    JobTestHandlers.StackOverflowHandler stackOverflowHandler() {
        return new JobTestHandlers.StackOverflowHandler();
    }

    @Bean
    JobTestHandlers.InterruptHandler interruptHandler() {
        return new JobTestHandlers.InterruptHandler();
    }

    @Bean
    JobTestHandlers.BlockHandler blockHandler(Recorder r) {
        return new JobTestHandlers.BlockHandler(r);
    }

    @Bean
    JobTestHandlers.PrioHandler prioHandler() {
        return new JobTestHandlers.PrioHandler();
    }

    @Bean
    JobTestHandlers.WorkerJdbcHandler workerJdbcHandler(Recorder r, com.english9x.platform.jobs.api.WorkerJdbc w) {
        return new JobTestHandlers.WorkerJdbcHandler(r, w);
    }

    @Bean
    JobTestHandlers.LeakHandler leakHandler() {
        return new JobTestHandlers.LeakHandler();
    }

    @Bean
    JobTestHandlers.ProbeHandler probeHandler(Recorder r) {
        return new JobTestHandlers.ProbeHandler(r);
    }

    @Bean
    JobTestHandlers.ContextHandler contextHandler(Recorder r, JdbcTemplate app, PlatformTransactionManager tm) {
        return new JobTestHandlers.ContextHandler(r, app, tm);
    }
}
