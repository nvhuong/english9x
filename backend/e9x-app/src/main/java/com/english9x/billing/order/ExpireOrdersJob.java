package com.english9x.billing.order;

import com.english9x.audit.api.SystemAuditLog;
import com.english9x.billing.internal.BillingProperties;
import com.english9x.platform.jobs.api.Job;
import com.english9x.platform.jobs.api.JobExecutor;
import com.english9x.platform.jobs.api.JobHandler;
import com.english9x.platform.jobs.api.JobResult;
import com.english9x.platform.jobs.api.WorkerJdbc;
import java.sql.Timestamp;
import java.time.Clock;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * {@code expire_orders} (spec section 11: "an order waiting more than 7 days becomes EXPIRED"; {@code e9x.billing.order-expiry}). Runs as the
 * worker role, which can only call {@code billing_expire_orders()}. The change and one SYSTEM audit record per order happen in ONE worker
 * transaction: no order expires without a record, and a record never stands for an order that did not expire.
 *
 * <p>Idempotent: an order that is no longer PENDING_PAYMENT is not touched, so a repeated or concurrent run changes nothing the second time.
 * Looks at state ("older than the TTL now"), never at "since the last run", so a missed tick is made up by the next one.
 */
@JobHandler("expire_orders")
class ExpireOrdersJob implements JobExecutor {

    private static final Logger LOG = LoggerFactory.getLogger(ExpireOrdersJob.class);

    private final WorkerJdbc worker;
    private final SystemAuditLog audit;
    private final BillingProperties props;
    private final Clock clock;

    ExpireOrdersJob(WorkerJdbc worker, SystemAuditLog audit, BillingProperties props, Clock clock) {
        this.worker = worker;
        this.audit = audit;
        this.props = props;
        this.clock = clock;
    }

    @Override
    public JobResult handle(Job job) {
        Instant now = clock.instant();
        Instant cutoff = now.minus(props.orderExpiry());
        Integer expired = worker.transaction().execute(status -> {
            List<UUID> ids = worker.jdbc().queryForList("select r_order_id from billing_expire_orders(?, ?)", UUID.class, Timestamp.from(cutoff), Timestamp.from(now));
            for (UUID id : ids) {
                audit.record("order.expire", "order", id, Map.of("reason", "unpaid-after-ttl", "ttl", props.orderExpiry().toString()));
            }
            return ids.size();
        });
        LOG.info("expire_orders: {} order(s) expired", expired);
        return JobResult.success();
    }
}
