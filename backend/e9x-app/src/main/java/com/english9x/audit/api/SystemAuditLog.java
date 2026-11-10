package com.english9x.audit.api;

import java.util.Map;
import java.util.UUID;

/**
 * The audit trail of what the SYSTEM itself changes (spec section 11: every write is audited), for background jobs that run as no person: the
 * actor kind is {@code SYSTEM}, with no actor and no organisation. A person's action goes through {@link AuditLog}; this is the only way for a
 * job handler to leave a record.
 *
 * <p>Call it from a {@code JobHandler}, <b>inside the worker transaction</b> ({@code WorkerJdbc#transaction()}) that makes the change, so the
 * record and the change stand or fall together. It writes through the worker role's own connection and throws
 * {@link org.springframework.transaction.IllegalTransactionStateException} outside such a transaction. The database accepts only the system
 * actions listed in {@code audit_log_record_system} (a migration adds one when a task lets the system change something new); any other
 * action is refused with a permission error. Detail rules are those of {@link AuditLog}: ids, counts and small facts, never a secret or a text
 * somebody wrote.
 */
public interface SystemAuditLog {

    void record(String action, String targetType, String targetId, Map<String, Object> detail);

    default void record(String action, String targetType, UUID targetId, Map<String, Object> detail) {
        record(action, targetType, targetId.toString(), detail);
    }
}
