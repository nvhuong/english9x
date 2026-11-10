package com.english9x.platform.jobs;

import com.english9x.audit.api.AuditRules;
import com.english9x.audit.api.SystemAuditLog;
import com.english9x.platform.jobs.api.WorkerJdbc;
import java.util.Map;
import org.springframework.stereotype.Component;
import org.springframework.transaction.IllegalTransactionStateException;
import org.springframework.transaction.support.TransactionSynchronizationManager;

/**
 * Writes a SYSTEM audit record through {@code audit_log_record_system()} on the worker connection, in the transaction the handler opened with
 * {@code WorkerJdbc#transaction()}. {@link WorkerJdbc} refuses any thread that is not running a job, so a request can never reach this. It lives
 * here and not in {@code audit} because the audit module may not depend on the platform (the platform already depends on it through org); the
 * rules of what a record may hold are the audit module's ({@link AuditRules}).
 */
@Component
class WorkerSystemAuditLog implements SystemAuditLog {

    private final WorkerJdbc worker;

    WorkerSystemAuditLog(WorkerJdbc worker) {
        this.worker = worker;
    }

    @Override
    public void record(String action, String targetType, String targetId, Map<String, Object> detail) {
        AuditRules.checkNames(action, targetType, targetId);
        String json = AuditRules.detailJson(detail);
        var jdbc = worker.jdbc();
        if (!TransactionSynchronizationManager.isActualTransactionActive() || !TransactionSynchronizationManager.hasResource(jdbc.getDataSource())) {
            throw new IllegalTransactionStateException("a system audit record is written in the worker transaction of the change it records "
                    + "(WorkerJdbc#transaction()); there is none");
        }
        jdbc.queryForObject("select count(*) from (select audit_log_record_system(?, ?, ?, ?::jsonb)) r", Integer.class, action, targetType, targetId, json);
    }
}
