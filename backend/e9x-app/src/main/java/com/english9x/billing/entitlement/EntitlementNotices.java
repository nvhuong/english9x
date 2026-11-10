package com.english9x.billing.entitlement;

import com.english9x.notification.api.EventType;
import com.english9x.notification.api.NotificationService;
import com.english9x.platform.jobs.api.WorkerJdbc;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.springframework.stereotype.Component;

/**
 * Shared by the two entitlement jobs. Reads the order-sourced entitlements ending inside a window through {@code billing_entitlements_due()}
 * (the worker has no table access), then tells each learner through {@link NotificationService}, which runs in its own transaction on the
 * application connection (the job queue refuses an enqueue inside a worker-role transaction). The two steps are NOT atomic, and need not be:
 * the dedupe key of a notice is the entitlement ({@code "<kind>:" + id}), so a run that stops half-way is simply run again and the people
 * already told are not told twice. The data of a notification is ids and the end date, never a course title.
 */
@Component
class EntitlementNotices {

    private record Due(UUID id, UUID userId, UUID courseId, Instant validUntil) {}

    private final WorkerJdbc worker;
    private final NotificationService notifications;

    EntitlementNotices(WorkerJdbc worker, NotificationService notifications) {
        this.worker = worker;
        this.notifications = notifications;
    }

    /** @return how many entitlements were in the window (notified now or earlier) */
    int notifyEnding(EventType type, String dedupePrefix, Instant after, Instant until) {
        List<Due> due = worker.jdbc().query("select r_id, r_user_id, r_course_id, r_valid_until from billing_entitlements_due(?, ?)",
                (rs, i) -> new Due(rs.getObject(1, UUID.class), rs.getObject(2, UUID.class), rs.getObject(3, UUID.class), rs.getTimestamp(4).toInstant()),
                Timestamp.from(after), Timestamp.from(until));
        for (Due d : due) {
            Map<String, Object> data = new LinkedHashMap<>();
            data.put("entitlementId", d.id());
            data.put("courseId", d.courseId());
            data.put("validUntil", d.validUntil().toString());
            notifications.notify(type, d.userId(), data, dedupePrefix + d.id());
        }
        return due.size();
    }
}
