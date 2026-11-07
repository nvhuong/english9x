package com.english9x.audit.internal;

import com.english9x.audit.api.AuditLog;
import java.util.Map;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/**
 * Writes one row to {@code audit_log} on the caller's connection and transaction (MANDATORY: no transaction is an error). The row's actor
 * and organisation are filled by the database from the session the transaction was bound to; the request role has no privilege to name
 * them. The statement has no RETURNING, so it needs no read access to the log.
 */
@Component
class JdbcAuditLog implements AuditLog {

    private final JdbcTemplate jdbc;
    private final AuditProperties props;

    JdbcAuditLog(JdbcTemplate jdbc, AuditProperties props) {
        this.jdbc = jdbc;
        this.props = props;
    }

    @Override
    @Transactional(propagation = Propagation.MANDATORY)
    public void record(String action, String targetType, String targetId, Map<String, Object> detail) {
        AuditEntries.checkNames(action, targetType, targetId);
        String json = AuditEntries.detailJson(detail, props.maxDetailBytes());
        if (!Boolean.TRUE.equals(jdbc.queryForObject("select e9x_current_user() is not null", Boolean.class))) {
            throw new IllegalStateException("cannot record '" + action + "': no person is bound to this transaction (bind the acting user first)");
        }
        jdbc.update("insert into audit_log(action, target_type, target_id, detail) values (?, ?, ?, ?::jsonb)", action, targetType, targetId, json);
    }
}
