package com.english9x.platform.jobs.api;

/**
 * The body of a {@link JobHandler} bean.
 *
 * <p>Runs on a worker thread with no request, no transaction and no user context. Business work that must see a user's
 * Row-Level-Security view binds {@code CurrentContextHolder.bind(userId, orgId)} (from ids it wrote into the payload
 * when it enqueued) and then runs its own {@code @Transactional} work on the application's transaction manager, exactly
 * like a request would. Work that needs the worker role itself (a grant added by the handler's own migration) goes
 * through {@link WorkerJdbc}.
 *
 * <p>A job runs at least once, not exactly once, so the handler must be idempotent. While it runs, the worker renews
 * the lease (heartbeat), so a slow handler is not re-claimed. A crashed worker's job is claimable again after the
 * lease. A concurrent second run of the same job nevertheless remains possible after a run exceeds
 * {@code e9x.jobs.<type>.max-run} (default 30 minutes; the heartbeat stops) or after a lost heartbeat; the older run's
 * late result is discarded.
 *
 * <p>Thread state: a handler must not leave a bound user context or the interrupt flag behind. The worker clears both
 * after the run (the context via {@link JobExecutionListener} beans) and logs the former as an error.
 */
public interface JobExecutor {

    JobResult handle(Job job) throws Exception;

    /**
     * Called just BEFORE the job moves to FAILED for good: the retries are used up, or the lease expired after the last
     * attempt. This is where a handler records its own terminal state (e.g. AI_FAILED plus an admin alert). Not called
     * for a job type that has no handler.
     *
     * <p>At-least-once: a crash between this call and the FAILED transition runs it again after the lease, so it must be
     * idempotent. A callback that throws is logged (exception class only) and cannot stop the FAILED transition.
     * Runs on the worker thread inside the job scope ({@link WorkerJdbc} is usable).
     */
    default void onExhausted(Job job, String reason) {}
}
