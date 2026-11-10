package com.english9x.platform.jobs.api;

import java.time.Duration;

/**
 * Declares a periodic job: expose one as a Spring bean and the platform queues a job of {@code type} once per {@code every}, from whichever
 * instance gets there first (see {@link JobQueue#enqueueOnce}). The tick is the time bucket {@code floor(now / every)}, so a restart, a second
 * instance or a slow poll never fires the same bucket twice; a tick that was missed (every instance down) is not made up, so the handler must
 * look at state ("what is due now") and never at "since the last run". The payload is empty: a recurring job carries no user data.
 *
 * <p>The handler is an ordinary {@link JobHandler}; it must be idempotent like any other, because a tick can still run twice (lease expiry).
 * Buckets are aligned to the epoch, so the first tick of a new deployment fires at its first poll.
 *
 * @param type  job type, matched against a {@link JobHandler}
 * @param every period, at least one second
 */
public record RecurringJob(String type, Duration every) {

    public RecurringJob {
        if (type == null || every == null || every.compareTo(Duration.ofSeconds(1)) < 0) {
            throw new IllegalArgumentException("a recurring job needs a type and a period of at least one second");
        }
    }
}
