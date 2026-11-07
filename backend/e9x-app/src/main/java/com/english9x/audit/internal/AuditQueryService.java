package com.english9x.audit.internal;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.swagger.v3.oas.annotations.media.Schema;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Reads the audit log for the system administrator's screen. The gate is the database's own SYS_ADMIN fact
 * ({@code e9x_is_sys_admin()}: the English9x flag AND a SYS_ADMIN grant, the same function the table's SELECT policy uses), asked first so a
 * caller who may not read gets 403 instead of an empty page that looks like an empty log. Nothing else (staff flag, ORG_ADMIN) reads.
 */
@Service
class AuditQueryService {

    private static final ObjectMapper JSON = new ObjectMapper();
    private static final int MAX_PAGE = 10_000;
    /** The window of dates a filter may name: wider than the log can ever span, narrow enough for the database's timestamp range. */
    private static final Instant MIN_DATE = Instant.parse("1970-01-01T00:00:00Z");
    private static final Instant MAX_DATE = Instant.parse("2200-01-01T00:00:00Z");

    @Schema(name = "AuditEntry")
    record Entry(
            @Schema(requiredMode = Schema.RequiredMode.REQUIRED) UUID id,
            @Schema(requiredMode = Schema.RequiredMode.REQUIRED, description = "The log's own order: larger = later") long seq,
            @Schema(requiredMode = Schema.RequiredMode.REQUIRED) Instant at,
            @Schema(requiredMode = Schema.RequiredMode.REQUIRED, description = "USER, or OPERATOR for what was done outside the application") String actorKind,
            @Schema(nullable = true) UUID actorId,
            @Schema(nullable = true, description = "Looked up now; null when the account no longer exists") String actorName,
            @Schema(nullable = true, description = "Looked up now; null when the account no longer exists") String actorEmail,
            @Schema(nullable = true, description = "The context the actor was in; null = English9x (for the actor kind OPERATOR: outside the application)") UUID orgId,
            @Schema(nullable = true) String orgName,
            @Schema(requiredMode = Schema.RequiredMode.REQUIRED) String action,
            @Schema(requiredMode = Schema.RequiredMode.REQUIRED) String targetType,
            @Schema(requiredMode = Schema.RequiredMode.REQUIRED) String targetId,
            @Schema(requiredMode = Schema.RequiredMode.REQUIRED, description = "Facts about the action. Plain data: show it as text.") Map<String, Object> detail,
            @Schema(nullable = true, description = "For a record about an invitation: the invited address, read from the invitation now (the log does not keep it, so that erasing the invitation erases it); null once the invitation is gone") String invitedEmail) {}

    @Schema(name = "AuditPage")
    record AuditPage(
            @Schema(requiredMode = Schema.RequiredMode.REQUIRED) List<Entry> items,
            @Schema(requiredMode = Schema.RequiredMode.REQUIRED, description = "Zero-based") int page,
            @Schema(requiredMode = Schema.RequiredMode.REQUIRED) int size,
            @Schema(requiredMode = Schema.RequiredMode.REQUIRED) boolean hasMore) {}

    record Query(String action, String targetType, String targetId, String actor, Instant from, Instant to, Integer page, Integer size) {}

    private final JdbcTemplate jdbc;
    private final AuditProperties props;

    AuditQueryService(JdbcTemplate jdbc, AuditProperties props) {
        this.jdbc = jdbc;
        this.props = props;
    }

    private void requireSysAdmin() {
        if (!Boolean.TRUE.equals(jdbc.queryForObject("select e9x_is_sys_admin()", Boolean.class))) {
            throw new AccessDeniedException("Access is denied");
        }
    }

    private static String blankToNull(String s) {
        return s == null || s.isBlank() ? null : s.strip();
    }

    /** A text filter: blank = none; a NUL character cannot be sent to the database, so it is a 400 here and not a 500 there. */
    private static String textFilter(String field, String value) {
        if (value != null && value.indexOf('\0') >= 0) {
            throw AuditProblems.validation(field, "Pattern", field + " must not contain a NUL character");
        }
        return blankToNull(value);
    }

    private static Instant dateFilter(String field, Instant value) {
        if (value != null && (value.isBefore(MIN_DATE) || value.isAfter(MAX_DATE))) {
            throw AuditProblems.validation(field, "Range", field + " must be between 1970-01-01 and 2200-01-01");
        }
        return value;
    }

    /** Newest first by the log's own sequence, which is unique, so the order is the same on every request and ties in time do not shuffle pages. */
    @Transactional(readOnly = true)
    public AuditPage list(Query q) {
        requireSysAdmin();
        int page = q.page() == null ? 0 : q.page();
        if (page < 0 || page > MAX_PAGE) {
            throw AuditProblems.validation("page", "Range", "page must be between 0 and " + MAX_PAGE);
        }
        int size = q.size() == null ? props.defaultPageSize() : q.size();
        if (size < 1) {
            throw AuditProblems.validation("size", "Min", "size must be at least 1");
        }
        size = Math.min(size, props.maxPageSize());

        StringBuilder where = new StringBuilder(" where true");
        List<Object> args = new ArrayList<>();
        String action = textFilter("action", q.action());
        if (action != null) {
            where.append(" and a.action = ?");
            args.add(action);
        }
        String targetType = textFilter("targetType", q.targetType());
        if (targetType != null) {
            where.append(" and a.target_type = ?");
            args.add(targetType);
        }
        String targetId = textFilter("targetId", q.targetId());
        if (targetId != null) {
            where.append(" and a.target_id = ?");
            args.add(targetId);
        }
        String actor = textFilter("actor", q.actor());
        if (actor != null) {
            UUID id = asUuid(actor);
            if (id != null) {
                where.append(" and a.actor_id = ?");
                args.add(id);
            } else {
                where.append(" and a.actor_id in (select u2.id from users u2 where lower(u2.email) = lower(?))");
                args.add(actor);
            }
        }
        Instant from = dateFilter("from", q.from());
        Instant to = dateFilter("to", q.to());
        if (from != null) {
            where.append(" and a.created_at >= ?");
            args.add(OffsetDateTime.ofInstant(from, ZoneOffset.UTC));
        }
        if (to != null) {
            where.append(" and a.created_at < ?");
            args.add(OffsetDateTime.ofInstant(to, ZoneOffset.UTC));
        }
        args.add(size + 1);
        args.add((long) page * size);
        List<Entry> rows = jdbc.query("""
                select a.id, a.seq, a.created_at, a.actor_kind, a.actor_id, u.display_name, u.email, a.org_id, o.name as org_name,
                       a.action, a.target_type, a.target_id, a.detail::text as detail, inv.email as invited_email
                  from audit_log a
                  left join users u on u.id = a.actor_id
                  left join organizations o on o.id = a.org_id
                  left join invitations inv on inv.id = case when a.target_type = 'invitation'
                       and a.target_id ~ '^[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}$' then a.target_id::uuid end""" + where + " order by a.seq desc limit ? offset ?",
                (rs, n) -> new Entry(rs.getObject("id", UUID.class), rs.getLong("seq"), rs.getTimestamp("created_at").toInstant(),
                        rs.getString("actor_kind"), rs.getObject("actor_id", UUID.class), rs.getString("display_name"), rs.getString("email"),
                        rs.getObject("org_id", UUID.class), rs.getString("org_name"), rs.getString("action"), rs.getString("target_type"),
                        rs.getString("target_id"), detail(rs.getString("detail")), rs.getString("invited_email")),
                args.toArray());
        boolean more = rows.size() > size;
        return new AuditPage(more ? rows.subList(0, size) : rows, page, size, more);
    }

    /** The actions that appear in the log, for the filter. Bounded: the vocabulary is small. */
    @Transactional(readOnly = true)
    public List<String> actions() {
        requireSysAdmin();
        return jdbc.queryForList("select distinct action from audit_log order by action limit 500", String.class);
    }

    private static UUID asUuid(String s) {
        try {
            return UUID.fromString(s);
        } catch (IllegalArgumentException e) {
            return null;
        }
    }

    private static Map<String, Object> detail(String json) {
        try {
            return JSON.readValue(json, new TypeReference<Map<String, Object>>() {});
        } catch (JsonProcessingException e) {
            throw new IllegalStateException(e);
        }
    }
}
