package com.english9x.grading.ai;

import com.english9x.grading.api.events.AttemptScored;
import com.english9x.notification.api.EventType;
import com.english9x.notification.api.NotificationService;
import java.util.Map;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.modulith.events.ApplicationModuleListener;
import org.springframework.stereotype.Component;

/**
 * "A preliminary score is ready" (spec 13) from {@code AttemptScored(official=false)}. The event is a hint: the narrow definer function {@code scores_has_preliminary} confirms the
 * person really holds a PRELIMINARY score of that attempt, so a forged event notifies nobody. One notification per attempt (dedupe key), however often the event is delivered.
 */
@Component
class AiNotifications {

    private final NotificationService notifications;
    private final JdbcTemplate jdbc;

    AiNotifications(NotificationService notifications, JdbcTemplate jdbc) {
        this.notifications = notifications;
        this.jdbc = jdbc;
    }

    @ApplicationModuleListener
    void on(AttemptScored e) {
        if (e.official() || !Boolean.TRUE.equals(jdbc.queryForObject("select scores_has_preliminary(?, ?)", Boolean.class, e.attemptId(), e.userId()))) {
            return;
        }
        notifications.notify(EventType.SCORE_PRELIMINARY, e.userId(), Map.of("attemptId", e.attemptId().toString()), "score-preliminary:" + e.attemptId());
    }
}
