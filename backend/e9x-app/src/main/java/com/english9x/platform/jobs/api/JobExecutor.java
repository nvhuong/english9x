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
 * <p>A job runs at least once, not exactly once: a lease that expires (crash, or a run longer than the lease) makes
 * the job claimable again, so the handler must be idempotent.
 */
@FunctionalInterface
public interface JobExecutor {

    JobResult handle(Job job) throws Exception;
}
