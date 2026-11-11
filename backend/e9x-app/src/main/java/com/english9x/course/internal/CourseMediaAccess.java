package com.english9x.course.internal;

import com.english9x.billing.api.Entitlements;
import com.english9x.content.media.api.MediaAccessPolicy;
import com.english9x.org.api.CurrentContextHolder;
import java.time.Clock;
import java.util.UUID;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

/**
 * Reclaims the access path P2.2 refused: a learner with an entitlement to a course whose published (or archived) version uses a file may get a link to it.
 * The database says which courses hold the file ({@code course_ids_with_media}); the entitlement is {@link Entitlements}' to decide.
 */
@Component
class CourseMediaAccess implements MediaAccessPolicy {

    private final JdbcTemplate jdbc;
    private final Entitlements entitlements;
    private final Clock clock;

    CourseMediaAccess(JdbcTemplate jdbc, Entitlements entitlements, Clock clock) {
        this.jdbc = jdbc;
        this.entitlements = entitlements;
        this.clock = clock;
    }

    @Override
    @Transactional(readOnly = true)
    public boolean mayPlay(UUID mediaId) {
        UUID user = CurrentContextHolder.current().map(CurrentContextHolder.Binding::userId).orElse(null);
        if (user == null) {
            return false;
        }
        return jdbc.queryForList("select course_ids_with_media(?)", UUID.class, mediaId).stream()
                .anyMatch(course -> entitlements.activeFor(user, course, clock.instant()));
    }
}
