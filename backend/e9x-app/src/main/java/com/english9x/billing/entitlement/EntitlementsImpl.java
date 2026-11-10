package com.english9x.billing.entitlement;

import com.english9x.billing.api.Entitlements;
import com.english9x.domain.billing.AccessPolicy;
import com.english9x.domain.billing.Entitlement;
import com.english9x.domain.billing.EntitlementSource;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * The learning-access gate. The rule itself is {@link AccessPolicy} of the domain; this only reads the person's entitlements for the course
 * through the application connection, so Row-Level Security answers for whoever is bound (a person sees their own rows, nobody else's).
 */
@Service
class EntitlementsImpl implements Entitlements {

    private final JdbcTemplate jdbc;

    EntitlementsImpl(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    @Override
    @Transactional(readOnly = true)
    public boolean activeFor(UUID userId, UUID courseId, Instant now) {
        if (userId == null || courseId == null || now == null) {
            return false;
        }
        return AccessPolicy.canStartLearning(load(userId, courseId), userId.toString(), courseId.toString(), now);
    }

    @Override
    @Transactional(readOnly = true)
    public boolean canContinueAttempt(UUID userId, UUID courseId, Instant attemptStartedAt) {
        if (userId == null || courseId == null || attemptStartedAt == null) {
            return false;
        }
        return AccessPolicy.canContinueAttempt(load(userId, courseId), userId.toString(), courseId.toString(), attemptStartedAt);
    }

    private List<Entitlement> load(UUID userId, UUID courseId) {
        return jdbc.query("select id, valid_from, valid_until, source_order_id from entitlements where user_id = ? and course_id = ?",
                (rs, i) -> new Entitlement(userId.toString(), courseId.toString(), rs.getTimestamp("valid_from").toInstant(),
                        rs.getTimestamp("valid_until").toInstant(),
                        rs.getObject("source_order_id") != null ? EntitlementSource.ORDER : EntitlementSource.SEAT, rs.getString("id")),
                userId, courseId);
    }
}
