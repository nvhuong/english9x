package com.english9x.audit.internal;

import com.english9x.audit.api.SystemAuditLog;
import com.english9x.platform.jobs.api.WorkerJdbc;
import java.util.Map;
import org.springframework.stereotype.Component;
import org.springframework.transaction.IllegalTransactionStateException;
import org.springframework.transaction.support.TransactionSynchronizationManager;

/**
 * Writes a SYSTEM record through {@code audit_log_record_system()} on the worker connection, in the transaction the handler opened with
 * {@code WorkerJdbc#transaction()}. {@link WorkerJdbc} refuses any thread that is not running a job, so a request can never reach this.
 */
@Component
class WorkerSystemAuditLog implements SystemAuditLog {

    private final WorkerJdbc worker;
    private final AuditProperties props;

    WorkerSystemAuditLog(WorkerJdbc worker, AuditProperties props) {
        this.worker = worker;
        this.props = props;
    }

    @Override
    public void record(String action, String targetType, String targetId, Map<String, Object> detail) {
        AuditEntries.checkNames(action, targetType, targetId);
        String json = AuditEntries.detailJson(detail, props.maxDetailBytes());
        var jdbc = worker.jdbc();
        if (!TransactionSynchronizationManager.isActualTransactionActive() || !TransactionSynchronizationManager.hasResource(jdbc.getDataSource())) {
            throw new IllegalTransactionStateException("a system audit record is written in the worker transaction of the change it records "
                    + "(WorkerJdbc#transaction()); there is none");
        }
        jdbc.queryForObject("select count(*) from (select audit_log_record_system(?, ?, ?, ?::jsonb)) r", Integer.class, action, targetType, targetId, json);
    }
}
