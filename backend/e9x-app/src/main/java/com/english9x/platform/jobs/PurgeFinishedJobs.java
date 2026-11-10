package com.english9x.platform.jobs;

import com.english9x.platform.jobs.api.Job;
import com.english9x.platform.jobs.api.JobExecutor;
import com.english9x.platform.jobs.api.JobHandler;
import com.english9x.platform.jobs.api.JobResult;
import com.english9x.platform.jobs.api.WorkerJdbc;
import java.sql.Timestamp;
import java.time.Clock;

/**
 * Retention of the queue itself: deletes SUCCEEDED / FAILED jobs older than {@code e9x.jobs.retention}. A job's payload may hold user data
 * (an id, a notification id) and a finished job has no further use but the log line, so it does not live for ever. The database refuses to
 * delete anything finished less than a day ago, whatever this handler asks (policy {@code jobs_worker_purge}).
 */
@JobHandler(PurgeFinishedJobs.TYPE)
class PurgeFinishedJobs implements JobExecutor {

    static final String TYPE = "purge_finished_jobs";

    private final WorkerJdbc worker;
    private final JobsProperties props;
    private final Clock clock;

    PurgeFinishedJobs(WorkerJdbc worker, JobsProperties props, Clock clock) {
        this.worker = worker;
        this.props = props;
        this.clock = clock;
    }

    @Override
    public JobResult handle(Job job) {
        Timestamp before = Timestamp.from(clock.instant().minus(props.retention()));
        worker.jdbc().update("delete from jobs where status in ('SUCCEEDED', 'FAILED') and finished_at < ?", before);
        return JobResult.success();
    }
}
