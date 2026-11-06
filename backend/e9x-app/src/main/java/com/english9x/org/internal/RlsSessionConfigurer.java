package com.english9x.org.internal;

import com.english9x.org.api.CurrentContextHolder;
import com.english9x.org.api.CurrentContextHolder.Binding;
import com.english9x.org.api.OrgContextDeniedException;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.Optional;
import java.util.UUID;
import org.springframework.stereotype.Component;

/**
 * Establishes the PostgreSQL session context that Row-Level Security reads, at the start of every transaction
 * (see {@link RlsHibernateJpaDialect}, which calls this right after BEGIN).
 *
 * <ul>
 *   <li>Every value is set with {@code set_config(name, value, true)}: transaction-local, so nothing survives COMMIT /
 *       ROLLBACK and nothing can leak to the next transaction on a pooled connection.
 *   <li>All four settings are (re)written on every transaction, to '' when there is no context, so even a value
 *       left behind at session level by buggy code is overwritten before any query runs.
 *   <li>{@code app.user_id} / {@code app.org_id} come from the {@link CurrentContextHolder} binding (set by trusted
 *       server code). {@code app.e9x_staff} / {@code app.org_admin} are never taken from any caller: they are derived
 *       here from role data in the database.
 *   <li>Values travel as bind parameters, never concatenated into SQL.
 * </ul>
 *
 * Flag rules (brief P1.3, rulings of the local wave):
 * <ul>
 *   <li>{@code app.e9x_staff = 'on'} only in the personal / English9x context (no org) for a user holding an
 *       AUTHOR / REVIEWER / TEACHER / SYS_ADMIN grant with {@code org_id} NULL.
 *   <li>{@code app.org_admin = 'on'} only in an org context for a member of that org holding an ORG_ADMIN grant
 *       for exactly that org.
 *   <li>Acting in an org the user is not a member of is refused ({@link OrgContextDeniedException}) as defence in
 *       depth behind the request layer's own check.
 * </ul>
 */
@Component
public class RlsSessionConfigurer {

    private static final String ON = "on";
    private static final String OFF = "";

    private static final String SET_IDENTITY = """
            select set_config('app.user_id', ?, true),
                   set_config('app.org_id', ?, true),
                   set_config('app.e9x_staff', '', true),
                   set_config('app.org_admin', '', true)
            """;
    private static final String SET_FLAGS = """
            select set_config('app.e9x_staff', ?, true),
                   set_config('app.org_admin', ?, true)
            """;
    private static final String HAS_ENGLISH9X_ROLE = """
            select exists (select 1 from role_grants g
                            where g.user_id = ? and g.org_id is null
                              and g.role in ('AUTHOR','REVIEWER','TEACHER','SYS_ADMIN'))
            """;
    private static final String IS_MEMBER =
            "select exists (select 1 from org_memberships m where m.user_id = ? and m.org_id = ?)";
    private static final String IS_ORG_ADMIN = """
            select exists (select 1 from role_grants g
                            where g.user_id = ? and g.org_id = ? and g.role = 'ORG_ADMIN')
            """;

    /** Must run inside the transaction that was just begun, on that transaction's connection. */
    public void configure(Connection connection) throws SQLException {
        Optional<Binding> binding = CurrentContextHolder.current();
        UUID userId = binding.map(Binding::userId).orElse(null);
        UUID orgId = binding.flatMap(Binding::orgId).orElse(null);

        try (PreparedStatement ps = connection.prepareStatement(SET_IDENTITY)) {
            ps.setString(1, userId == null ? OFF : userId.toString());
            ps.setString(2, orgId == null ? OFF : orgId.toString());
            ps.execute();
        }
        if (userId == null) {
            return; // no context: every flag stays off and RLS answers with no rows
        }

        boolean staff = false;
        boolean orgAdmin = false;
        if (orgId == null) {
            staff = exists(connection, HAS_ENGLISH9X_ROLE, userId, null);
        } else {
            if (!exists(connection, IS_MEMBER, userId, orgId)) {
                throw new OrgContextDeniedException(userId, orgId);
            }
            orgAdmin = exists(connection, IS_ORG_ADMIN, userId, orgId);
        }

        try (PreparedStatement ps = connection.prepareStatement(SET_FLAGS)) {
            ps.setString(1, staff ? ON : OFF);
            ps.setString(2, orgAdmin ? ON : OFF);
            ps.execute();
        }
    }

    private static boolean exists(Connection connection, String sql, UUID userId, UUID orgId) throws SQLException {
        try (PreparedStatement ps = connection.prepareStatement(sql)) {
            ps.setObject(1, userId);
            if (orgId != null) {
                ps.setObject(2, orgId);
            }
            try (ResultSet rs = ps.executeQuery()) {
                return rs.next() && rs.getBoolean(1);
            }
        }
    }
}
