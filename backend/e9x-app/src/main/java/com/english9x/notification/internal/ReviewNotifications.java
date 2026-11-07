package com.english9x.notification.internal;

import com.english9x.notification.api.EventType;
import com.english9x.notification.api.NotificationService;
import com.english9x.workflow.api.events.ReviewApproved;
import com.english9x.workflow.api.events.ReviewChangesRequested;
import com.english9x.workflow.api.events.ReviewRequested;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.modulith.events.ApplicationModuleListener;
import org.springframework.stereotype.Component;

/**
 * Spec §13 "content waits for review" / "changes requested / published", from the workflow's events. Runs after the workflow's
 * transaction committed, on a pool thread with no user bound (see platform.events.AsyncContextCleaner), delivered at least once by the
 * persistent event registry. Idempotent: every notification has a dedupe key derived from the review id, so a redelivered event creates nothing
 * new. Recipients: REVIEWERs of the exam in the content's context come from the narrow SECURITY DEFINER function
 * {@code notification_reviewers_of_review(reviewId)} (a request cannot list an organisation's people); authors come in the event. The data
 * holds ids, the exam code and the content type, never a title.
 */
@Component
class ReviewNotifications {

    private final NotificationService notifications;
    private final JdbcTemplate jdbc;

    ReviewNotifications(NotificationService notifications, JdbcTemplate jdbc) {
        this.notifications = notifications;
        this.jdbc = jdbc;
    }

    @ApplicationModuleListener
    void on(ReviewRequested e) {
        Map<String, Object> data = data(e.type(), e.examCode(), e.reviewId(), e.ownerOrgId());
        for (UUID reviewer : jdbc.queryForList("select r_user_id from notification_reviewers_of_review(?)", UUID.class, e.reviewId())) {
            notifications.notify(EventType.REVIEW_REQUESTED, reviewer, data, "review-requested:" + e.reviewId());
        }
    }

    @ApplicationModuleListener
    void on(ReviewChangesRequested e) {
        notifications.notify(EventType.REVIEW_CHANGES_REQUESTED, e.authorId(), data(e.type(), e.examCode(), e.reviewId(), e.ownerOrgId()),
                "review-changes-requested:" + e.reviewId());
    }

    @ApplicationModuleListener
    void on(ReviewApproved e) {
        notifications.notify(EventType.CONTENT_PUBLISHED, e.authorId(), data(e.type(), e.examCode(), e.reviewId(), e.ownerOrgId()),
                "review-approved:" + e.reviewId());
    }

    private static Map<String, Object> data(String type, String examCode, UUID reviewId, Optional<UUID> orgId) {
        Map<String, Object> data = new LinkedHashMap<>();
        data.put("contentType", type);
        data.put("examCode", examCode);
        data.put("reviewId", reviewId.toString());
        orgId.ifPresent(o -> data.put("orgId", o.toString()));
        return data;
    }
}
