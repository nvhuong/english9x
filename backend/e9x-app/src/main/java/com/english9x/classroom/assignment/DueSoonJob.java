package com.english9x.classroom.assignment;

import com.english9x.notification.api.EventType;
import com.english9x.notification.api.NotificationService;
import com.english9x.platform.jobs.api.Job;
import com.english9x.platform.jobs.api.JobExecutor;
import com.english9x.platform.jobs.api.JobHandler;
import com.english9x.platform.jobs.api.JobResult;
import com.english9x.platform.jobs.api.WorkerJdbc;
import java.sql.Timestamp;
import java.time.Clock;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * {@code assignment_due_soon}: tells each learner who has not handed an assignment in that it falls due within {@code e9x.classroom.due-soon} (24 hours;
 * {@code ASSIGNMENT_DUE_SOON}). The worker reads who through {@code classroom_due_soon()} (it has no table access); the notice goes out through
 * {@link NotificationService} in its own transaction. Once per person and assignment (dedupe key {@code assignment-due-soon:<assignmentId>}), so the hourly
 * run after the first finds the notice already made, and a run that stopped half-way is simply run again.
 */
@JobHandler("assignment_due_soon")
class DueSoonJob implements JobExecutor {

    private static final Logger LOG = LoggerFactory.getLogger(DueSoonJob.class);

    private record Due(UUID assignmentId, UUID userId, Instant dueAt) {}

    private final WorkerJdbc worker;
    private final NotificationService notifications;
    private final ClassroomProperties props;
    private final Clock clock;

    DueSoonJob(WorkerJdbc worker, NotificationService notifications, ClassroomProperties props, Clock clock) {
        this.worker = worker;
        this.notifications = notifications;
        this.props = props;
        this.clock = clock;
    }

    @Override
    public JobResult handle(Job job) {
        Instant now = clock.instant();
        List<Due> due = worker.jdbc().query("select r_assignment_id, r_user_id, r_due_at from classroom_due_soon(?, ?)",
                (rs, i) -> new Due(rs.getObject(1, UUID.class), rs.getObject(2, UUID.class), rs.getTimestamp(3).toInstant()),
                Timestamp.from(now), Timestamp.from(now.plus(props.dueSoon())));
        for (Due d : due) {
            notifications.notify(EventType.ASSIGNMENT_DUE_SOON, d.userId(), Map.of("assignmentId", d.assignmentId().toString(), "dueAt", d.dueAt().toString()),
                    "assignment-due-soon:" + d.assignmentId());
        }
        LOG.info("assignment_due_soon: {} learner(s) with work due soon", due.size());
        return JobResult.success();
    }
}
