package com.english9x.platform.jobs;

import com.english9x.platform.jobs.api.Job;
import com.english9x.platform.jobs.api.JobExecutor;
import com.english9x.platform.jobs.api.JobResult;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.SmartLifecycle;

/**
 * Polls the queue with {@code e9x.jobs.workers} threads and runs the claimed jobs. The threads exist only when this
 * bean exists, i.e. when {@code app.worker.enabled} is true. Each thread waits one poll interval before its first
 * poll; tests call {@link #pollOnce()} themselves.
 *
 * <p>Logs carry job id, type and attempt only. Never the payload, and never an exception message (those quote data).
 */
class JobWorker implements SmartLifecycle {

    private static final Logger log = LoggerFactory.getLogger(JobWorker.class);

    private final JobStore store;
    private final HandlerRegistry handlers;
    private final RetryPolicy retryPolicy;
    private final WorkerDatabase database;
    private final Clock clock;
    private final int workers;
    private final long pollMillis;

    private final Object monitor = new Object();
    private final List<Thread> threads = new ArrayList<>();
    private volatile boolean running;

    JobWorker(JobStore store, HandlerRegistry handlers, RetryPolicy retryPolicy, WorkerDatabase database,
            JobsProperties properties, Clock clock) {
        if (properties.workers() < 1) {
            throw new IllegalArgumentException("e9x.jobs.workers must be at least 1");
        }
        if (properties.pollInterval().isNegative() || properties.pollInterval().isZero()) {
            throw new IllegalArgumentException("e9x.jobs.poll-interval must be positive");
        }
        this.store = store;
        this.handlers = handlers;
        this.retryPolicy = retryPolicy;
        this.database = database;
        this.clock = clock;
        this.workers = properties.workers();
        this.pollMillis = properties.pollInterval().toMillis();
    }

    /** Claims and runs at most one job. @return false when nothing was due */
    boolean pollOnce() {
        Optional<ClaimedJob> claimed = store.claim(clock.instant());
        if (claimed.isEmpty()) {
            return false;
        }
        process(claimed.get());
        return true;
    }

    private void process(ClaimedJob claimed) {
        Job job = claimed.job();
        Optional<JobExecutor> handler = handlers.find(job.type());
        if (handler.isEmpty()) {
            log.error("job {} type={}: no handler registered, marking FAILED", job.id(), job.type());
            finish(claimed, store.fail(claimed, "no handler registered for job type " + job.type(), clock.instant()));
            return;
        }
        int maxAttempts = retryPolicy.maxAttempts(job.type());
        if (job.attempt() > maxAttempts) {
            // The previous run's lease expired (crash or overrun) after the last allowed attempt: do not run it again.
            log.error("job {} type={}: lease expired after the last attempt ({} of {}), marking FAILED",
                    job.id(), job.type(), job.attempt() - 1, maxAttempts);
            finish(claimed, store.fail(claimed, "lease expired after the last attempt (" + (job.attempt() - 1) + " of "
                    + maxAttempts + "); not run again", clock.instant()));
            return;
        }

        JobResult result;
        try (WorkerDatabase.JobScope ignored = database.enterJob()) {
            result = handler.get().handle(job);
            if (result == null) {
                result = JobResult.failure("handler returned null");
            }
        } catch (VirtualMachineError e) {
            throw e;
        } catch (Throwable t) {
            // The class name only: exception messages (parse errors, SQL) can quote the payload.
            log.warn("job {} type={} attempt={}: handler threw {}", job.id(), job.type(), job.attempt(), t.getClass().getName());
            result = JobResult.failure(t.getClass().getName());
        }

        Instant now = clock.instant();
        boolean recorded;
        if (result.isSuccess()) {
            recorded = store.succeed(claimed, now);
            log.debug("job {} type={} attempt={}: succeeded", job.id(), job.type(), job.attempt());
        } else {
            List<Duration> delays = retryPolicy.delays(job.type());
            if (job.attempt() <= delays.size()) {
                Duration delay = delays.get(job.attempt() - 1);
                recorded = store.retryAt(claimed, now.plus(delay), result.error(), now);
                log.info("job {} type={} attempt={}: failed, retry in {}", job.id(), job.type(), job.attempt(), delay);
            } else {
                recorded = store.fail(claimed, result.error(), now);
                log.warn("job {} type={} attempt={}: failed for good after the last retry", job.id(), job.type(), job.attempt());
            }
        }
        finish(claimed, recorded);
    }

    private void finish(ClaimedJob claimed, boolean recorded) {
        if (!recorded) {
            log.warn("job {} type={} attempt={}: lease lost before the result was recorded (another worker re-claimed it); "
                    + "result discarded", claimed.job().id(), claimed.job().type(), claimed.job().attempt());
        }
    }

    // ------------------------------------------------------------------ lifecycle

    @Override
    public synchronized void start() {
        if (running) {
            return;
        }
        running = true;
        threads.clear();
        for (int i = 0; i < workers; i++) {
            Thread t = new Thread(this::loop, "e9x-job-worker-" + i);
            threads.add(t);
            t.start();
        }
        log.info("job worker started: {} threads, poll interval {} ms", workers, pollMillis);
    }

    @Override
    public synchronized void stop() {
        running = false;
        synchronized (monitor) {
            monitor.notifyAll();
        }
        for (Thread t : threads) {
            try {
                t.join(30_000);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                break;
            }
        }
        threads.clear();
    }

    @Override
    public boolean isRunning() {
        return running;
    }

    int liveThreads() {
        synchronized (this) {
            return (int) threads.stream().filter(Thread::isAlive).count();
        }
    }

    private void loop() {
        sleep();
        while (running) {
            boolean worked = false;
            try {
                worked = pollOnce();
            } catch (RuntimeException e) {
                log.error("job poll failed: {}", e.getClass().getName());
            }
            if (!worked) {
                sleep();
            }
        }
    }

    private void sleep() {
        synchronized (monitor) {
            if (!running) {
                return;
            }
            try {
                monitor.wait(pollMillis);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                running = false;
            }
        }
    }
}
