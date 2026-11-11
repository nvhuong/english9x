package com.english9x.classroom.assignment;

import com.english9x.notification.api.EventType;
import com.english9x.notification.api.NotificationService;
import java.util.Map;
import java.util.UUID;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.modulith.events.ApplicationModuleListener;
import org.springframework.stereotype.Component;

/**
 * "You have a new assignment" (spec 13, {@code ASSIGNMENT_NEW}) for each learner of the class. The event is a hint: a person is told only when the database
 * confirms they are a member of the class of that assignment. One notification per person and assignment (dedupe key), however often the event arrives.
 */
@Component
class AssignmentNotifications {

    private final NotificationService notifications;
    private final JdbcTemplate jdbc;

    AssignmentNotifications(NotificationService notifications, JdbcTemplate jdbc) {
        this.notifications = notifications;
        this.jdbc = jdbc;
    }

    @ApplicationModuleListener
    void on(AssignmentCreated e) {
        for (UUID user : e.recipients()) {
            if (Boolean.TRUE.equals(jdbc.queryForObject("select classroom_is_recipient(?, ?)", Boolean.class, e.assignmentId(), user))) {
                notifications.notify(EventType.ASSIGNMENT_NEW, user, Map.of("assignmentId", e.assignmentId().toString()), "assignment-new:" + e.assignmentId());
            }
        }
    }
}
