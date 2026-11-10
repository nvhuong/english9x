package com.english9x.billing.entitlement;

import com.english9x.org.api.CurrentContextHolder;
import io.swagger.v3.oas.annotations.media.Schema;
import java.time.Clock;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

/** The caller's own entitlements, past ones included (an expired learner still sees what they had, and their old results are not gated). */
@RestController
class MyEntitlements {

    @Schema(name = "EntitlementView")
    record EntitlementView(
            @Schema(requiredMode = Schema.RequiredMode.REQUIRED) UUID id,
            @Schema(requiredMode = Schema.RequiredMode.REQUIRED) UUID courseId,
            @Schema(nullable = true, description = "Null when the course is no longer visible to the caller") String courseCode,
            @Schema(requiredMode = Schema.RequiredMode.REQUIRED) Instant validFrom,
            @Schema(requiredMode = Schema.RequiredMode.REQUIRED) Instant validUntil,
            @Schema(requiredMode = Schema.RequiredMode.REQUIRED, description = "Valid right now: new lessons and attempts can be opened") boolean active,
            @Schema(requiredMode = Schema.RequiredMode.REQUIRED, description = "ORDER (a paid plan) or SEAT (an organisation's licence)") String source) {}

    private final JdbcTemplate jdbc;
    private final Clock clock;

    MyEntitlements(JdbcTemplate jdbc, Clock clock) {
        this.jdbc = jdbc;
        this.clock = clock;
    }

    @GetMapping("/api/me/entitlements")
    @Transactional(readOnly = true)
    List<EntitlementView> mine() {
        UUID user = CurrentContextHolder.current().orElseThrow(() -> new AccessDeniedException("no user")).userId();
        Instant now = clock.instant();
        return jdbc.query("""
                select e.id, e.course_id, c.code, e.valid_from, e.valid_until, e.source_order_id
                  from entitlements e left join courses c on c.id = e.course_id
                 where e.user_id = ? order by e.valid_until desc, e.id
                """, (rs, i) -> {
            Instant from = rs.getTimestamp("valid_from").toInstant();
            Instant until = rs.getTimestamp("valid_until").toInstant();
            String code = rs.getString("code");
            return new EntitlementView(rs.getObject("id", UUID.class), rs.getObject("course_id", UUID.class), code == null ? null : code.strip(), from, until,
                    !now.isBefore(from) && now.isBefore(until), rs.getObject("source_order_id") != null ? "ORDER" : "SEAT");
        }, user);
    }
}
