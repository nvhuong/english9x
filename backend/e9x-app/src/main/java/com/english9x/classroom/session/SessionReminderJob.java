package com.english9x.classroom.session;

import com.english9x.notification.api.EventType;
import com.english9x.notification.api.NotificationService;
import com.english9x.platform.jobs.api.Job;
import com.english9x.platform.jobs.api.JobExecutor;
import com.english9x.platform.jobs.api.JobHandler;
import com.english9x.platform.jobs.api.JobResult;
import com.english9x.platform.jobs.api.WorkerJdbc;
import java.sql.Timestamp;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;

/**
 * {@code session_reminder}: tells the learners of a class and its teacher that a session starts within {@code e9x.notification.session-reminder} (1 hour;
 * {@code SESSION_REMINDER}). The worker reads who through {@code classroom_sessions_to_remind()} (it has no table access); the notice goes out through
 * {@link NotificationService} in its own transaction. Once per person and session (dedupe key {@code session-reminder:<sessionId>}), so the runs after the
 * first find the notice already made and a run that stopped half-way is simply run again.
 */
@JobHandler("session_reminder")
class SessionReminderJob implements JobExecutor {

    private static final Logger LOG = LoggerFactory.getLogger(SessionReminderJob.class);

    private record Due(UUID sessionId, UUID userId, Instant startsAt) {}

    private final WorkerJdbc worker;
    private final NotificationService notifications;
    private final Duration lead;
    private final Clock clock;

    SessionReminderJob(WorkerJdbc worker, NotificationService notifications, @Value("${e9x.notification.session-reminder:1h}") Duration lead, Clock clock) {
        this.worker = worker;
        this.notifications = notifications;
        this.lead = lead;
        this.clock = clock;
    }

    @Override
    public JobResult handle(Job job) {
        Instant now = clock.instant();
        List<Due> due = worker.jdbc().query("select r_session_id, r_user_id, r_starts_at from classroom_sessions_to_remind(?, ?)",
                (rs, i) -> new Due(rs.getObject(1, UUID.class), rs.getObject(2, UUID.class), rs.getTimestamp(3).toInstant()), Timestamp.from(now), Timestamp.from(now.plus(lead)));
        for (Due d : due) {
            notifications.notify(EventType.SESSION_REMINDER, d.userId(), Map.of("sessionId", d.sessionId().toString(), "startsAt", d.startsAt().toString()),
                    "session-reminder:" + d.sessionId());
        }
        LOG.info("session_reminder: {} person(s) told about a session starting soon", due.size());
        return JobResult.success();
    }
}
