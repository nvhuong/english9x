package com.english9x.platform.jobs;

import com.english9x.platform.jobs.api.Job;
import com.english9x.platform.jobs.api.JobExecutionListener;
import com.english9x.platform.jobs.api.JobExecutor;
import com.english9x.platform.jobs.api.JobResult;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.ScheduledThreadPoolExecutor;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.SmartLifecycle;

/**
 * Polls the queue and runs the claimed jobs. The threads exist only when this bean exists, i.e. when
 * {@code app.worker.enabled} is true: {@code e9x.jobs.workers} general threads (any job type) plus, when
 * {@code e9x.jobs.priority-types} is not empty, one dedicated thread that claims only those types. The general
 * threads claim priority types too; the dedicated thread is what keeps time-critical jobs from waiting while every
 * general thread is busy with slow work. Claiming itself is FIFO by run_at. Each thread waits one poll interval
 * before its first poll; tests call {@link #pollOnce()} themselves.
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
    private final Duration heartbeat;
    private final Set<String> priorityTypes;
    private final List<JobExecutionListener> listeners;

    private final Object monitor = new Object();
    private final List<Thread> threads = new ArrayList<>();
    private volatile boolean running;
    private ScheduledThreadPoolExecutor heartbeats;

    JobWorker(JobStore store, HandlerRegistry handlers, RetryPolicy retryPolicy, WorkerDatabase database,
            JobsProperties properties, Clock clock, List<JobExecutionListener> listeners) {
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
        this.heartbeat = properties.effectiveHeartbeat();
        if (heartbeat.isNegative() || heartbeat.isZero()) {
            throw new IllegalArgumentException("e9x.jobs.heartbeat-interval must be positive");
        }
        List<String> pt = properties.priorityTypes() == null ? List.of() : properties.priorityTypes();
        pt.forEach(JobTypes::validate);
        this.priorityTypes = Set.copyOf(pt);
        this.listeners = List.copyOf(listeners);
    }

    /** Claims and runs at most one job of any type. @return false when nothing was due */
    boolean pollOnce() {
        return poll(Set.of());
    }

    /** What the dedicated thread does: only the priority types. */
    boolean pollPriorityOnce() {
        return poll(priorityTypes);
    }

    private boolean poll(Set<String> types) {
        Instant now = clock.instant();
        Optional<ClaimedJob> claimed = types.isEmpty() ? store.claim(now) : store.claim(now, types);
        if (claimed.isEmpty()) {
            return false;
        }
        process(claimed.get());
        return true;
    }

    private void process(ClaimedJob claimed) {
        Job job = claimed.job();
        if (claimed.reclaimed()) {
            log.warn("job {} type={} attempt={}: took over an expired lease (the previous run crashed, hung or overran)",
                    job.id(), job.type(), job.attempt());
        }
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
            String reason = "lease expired after the last attempt (" + (job.attempt() - 1) + " of " + maxAttempts + "); not run again";
            exhausted(handler.get(), job, reason);
            finish(claimed, store.fail(claimed, reason, clock.instant()));
            return;
        }

        JobResult result;
        ScheduledFuture<?> beat = startHeartbeat(claimed);
        try (WorkerDatabase.JobScope ignored = database.enterJob()) {
            result = handler.get().handle(job);
            if (result == null) {
                result = JobResult.failure("handler returned null");
            }
        } catch (Throwable t) {
            // Any Throwable, a StackOverflowError included, is this job's failure and must not kill the thread.
            // The class name only: exception messages (parse errors, SQL) can quote the payload.
            log.warn("job {} type={} attempt={}: handler threw {}", job.id(), job.type(), job.attempt(), t.getClass().getName());
            result = JobResult.failure(t.getClass().getName());
        } finally {
            beat.cancel(false);
            Thread.interrupted(); // a handler may leave the flag set; it must not leak into the next job or the DB calls below
        }
        afterJob(job);

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
                exhausted(handler.get(), job, JobStore.limit(result.error()));
                recorded = store.fail(claimed, result.error(), clock.instant());
                log.warn("job {} type={} attempt={}: failed for good after the last retry", job.id(), job.type(), job.attempt());
            }
        }
        finish(claimed, recorded);
    }

    /** onExhausted runs before the FAILED transition; whatever it throws is logged (class only) and ignored. */
    private void exhausted(JobExecutor handler, Job job, String reason) {
        try (WorkerDatabase.JobScope ignored = database.enterJob()) {
            handler.onExhausted(job, reason);
        } catch (Throwable t) {
            log.error("job {} type={}: onExhausted callback threw {}", job.id(), job.type(), t.getClass().getName());
        } finally {
            Thread.interrupted();
        }
        afterJob(job);
    }

    private void afterJob(Job job) {
        for (JobExecutionListener l : listeners) {
            try {
                l.afterJob(job);
            } catch (Throwable t) {
                log.error("job {} type={}: after-job listener {} threw {}", job.id(), job.type(),
                        l.getClass().getSimpleName(), t.getClass().getName());
            }
        }
    }

    private void finish(ClaimedJob claimed, boolean recorded) {
        if (!recorded) {
            log.warn("job {} type={} attempt={}: lease lost before the result was recorded (another worker re-claimed it); "
                    + "result discarded", claimed.job().id(), claimed.job().type(), claimed.job().attempt());
        }
    }

    // ------------------------------------------------------------------ heartbeat

    /** Thrown inside the heartbeat task to end its schedule. */
    private static final class StopHeartbeat extends RuntimeException {
        private static final long serialVersionUID = 1L;

        StopHeartbeat() {
            super(null, null, false, false);
        }
    }

    private ScheduledFuture<?> startHeartbeat(ClaimedJob claimed) {
        Job job = claimed.job();
        Duration maxRun = retryPolicy.maxRun(job.type());
        Instant started = clock.instant();
        return heartbeats().scheduleWithFixedDelay(() -> {
            try {
                Instant now = clock.instant();
                if (Duration.between(started, now).compareTo(maxRun) > 0) {
                    log.warn("job {} type={} attempt={}: still running after max-run {}; heartbeat stopped, the job becomes "
                            + "reclaimable and may run a second time", job.id(), job.type(), job.attempt(), maxRun);
                    throw new StopHeartbeat();
                }
                if (!store.renew(claimed, now)) {
                    log.warn("job {} type={} attempt={}: lease lost, heartbeat stopped", job.id(), job.type(), job.attempt());
                    throw new StopHeartbeat();
                }
            } catch (StopHeartbeat e) {
                throw e;
            } catch (Throwable t) {
                log.error("job {} type={}: heartbeat failed: {}", job.id(), job.type(), t.getClass().getName());
            }
        }, heartbeat.toMillis(), heartbeat.toMillis(), java.util.concurrent.TimeUnit.MILLISECONDS);
    }

    private synchronized ScheduledThreadPoolExecutor heartbeats() {
        if (heartbeats == null || heartbeats.isShutdown()) {
            ScheduledThreadPoolExecutor e = new ScheduledThreadPoolExecutor(1, r -> {
                Thread t = new Thread(r, "e9x-job-heartbeat");
                t.setDaemon(true);
                return t;
            });
            e.setRemoveOnCancelPolicy(true);
            heartbeats = e;
        }
        return heartbeats;
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
            spawn("e9x-job-worker-" + i, false);
        }
        if (!priorityTypes.isEmpty()) {
            spawn("e9x-job-worker-priority", true);
        }
        log.info("job worker started: {} general threads{}, poll interval {} ms", workers,
                priorityTypes.isEmpty() ? "" : " + 1 priority thread for " + priorityTypes, pollMillis);
    }

    private void spawn(String name, boolean priority) {
        Thread t = new Thread(() -> loop(priority), name);
        threads.add(t);
        t.start();
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
        if (heartbeats != null) {
            heartbeats.shutdownNow();
        }
    }

    @Override
    public boolean isRunning() {
        return running;
    }

    int expectedThreads() {
        return workers + (priorityTypes.isEmpty() ? 0 : 1);
    }

    int liveThreads() {
        synchronized (this) {
            return (int) threads.stream().filter(Thread::isAlive).count();
        }
    }

    private void loop(boolean priority) {
        sleep();
        while (running) {
            boolean worked = false;
            try {
                worked = priority ? pollPriorityOnce() : pollOnce();
            } catch (Throwable t) {
                // Nothing may end a polling thread: log the class and keep going.
                log.error("job poll failed: {}", t.getClass().getName());
            }
            Thread.interrupted();
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
                // Shutdown is signalled through `running` and notifyAll, never through interrupts; an interrupt
                // here only ends this wait early and must not stop the other workers.
            }
        }
    }
}
