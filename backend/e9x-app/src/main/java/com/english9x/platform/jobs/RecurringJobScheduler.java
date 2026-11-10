package com.english9x.platform.jobs;

import com.english9x.platform.jobs.api.JobQueue;
import com.english9x.platform.jobs.api.RecurringJob;
import java.time.Clock;
import java.time.Instant;
import java.util.List;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * Queues the tick of every {@link RecurringJob} bean. Every instance runs this; the tick's dedupe key is {@code tick:<bucket>} with
 * {@code bucket = floor(epochSecond / every)}, so however many instances (or polls) try, a bucket is queued once
 * ({@link JobQueue#enqueueOnce}). It enqueues in a transaction of its own, with no user bound: the jobs table takes only INSERT from the
 * request role and the payload is empty.
 */
@Component
class RecurringJobScheduler {

    private static final Logger LOG = LoggerFactory.getLogger(RecurringJobScheduler.class);

    private final JobQueue queue;
    private final ObjectProvider<RecurringJob> jobs;
    private final TransactionTemplate tx;
    private final Clock clock;
    private final JobsProperties props;

    RecurringJobScheduler(JobQueue queue, ObjectProvider<RecurringJob> jobs, PlatformTransactionManager tm, Clock clock, JobsProperties props) {
        this.queue = queue;
        this.jobs = jobs;
        this.tx = new TransactionTemplate(tm);
        this.clock = clock;
        this.props = props;
    }

    @Scheduled(initialDelayString = "${e9x.jobs.recurring.poll:1m}", fixedDelayString = "${e9x.jobs.recurring.poll:1m}")
    void scheduled() {
        if (!props.recurring().enabled()) {
            return;
        }
        try {
            tick();
        } catch (RuntimeException e) {
            LOG.error("recurring jobs: could not queue the ticks ({})", e.getClass().getName()); // the next poll tries again
        }
    }

    /** Queues the tick of the current bucket of every recurring job that has none yet; returns how many it queued. */
    int tick() {
        Instant now = clock.instant();
        List<RecurringJob> all = jobs.orderedStream().toList();
        int queued = 0;
        for (RecurringJob job : all) {
            long bucket = Math.floorDiv(now.getEpochSecond(), job.every().getSeconds());
            Boolean added = tx.execute(status -> queue.enqueueOnce(job.type(), null, now, "tick:" + bucket));
            if (Boolean.TRUE.equals(added)) {
                queued++;
            }
        }
        return queued;
    }
}
