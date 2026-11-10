package com.english9x.billing.order;

import static org.junit.jupiter.api.Assertions.assertEquals;

import com.english9x.billing.BillingIT;
import java.sql.Timestamp;
import java.time.Duration;
import java.time.Instant;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class ExpireOrdersJobIT extends BillingIT {

    private UUID order(String status, Instant createdAt) {
        UUID u = user("o");
        UUID plan = plan(100_000, 1, 1, true);
        UUID id = UUID.randomUUID();
        String code = UUID.randomUUID().toString().replace("-", "").substring(0, 6).toUpperCase();
        su.update("insert into orders(id,code,user_id,plan_id,plan_months,amount_vnd,created_at,status,confirmed_at) values (?,?,?,?,1,1000,?,?,?)",
                id, code, u, plan, Timestamp.from(createdAt), status, "CONFIRMED".equals(status) ? Timestamp.from(createdAt) : null);
        return id;
    }

    private String status(UUID id) {
        return su.queryForObject("select status from orders where id = ?", String.class, id);
    }

    @Test
    void pendingOrderExpiresAfterConfiguredTtl() {
        Instant now = clock.instant();
        UUID fresh = order("PENDING_PAYMENT", now.minus(Duration.ofDays(7)).plusSeconds(60));
        UUID old = order("PENDING_PAYMENT", now.minus(Duration.ofDays(7)));
        UUID confirmed = order("CONFIRMED", now.minus(Duration.ofDays(30)));
        runJob("expire_orders");
        assertEquals("PENDING_PAYMENT", status(fresh), "not yet 7 days");
        assertEquals("EXPIRED", status(old));
        assertEquals("CONFIRMED", status(confirmed), "a paid order never expires");
        assertEquals(1, su.queryForObject("select count(*) from audit_log where action = 'order.expire' and actor_kind = 'SYSTEM' and actor_id is null and org_id is null and target_id = ?",
                Integer.class, old.toString()));
        clock.advance(Duration.ofMinutes(2));
        runJob("expire_orders");
        runJob("expire_orders");
        assertEquals("EXPIRED", status(fresh), "a minute later its seven days are over");
        assertEquals(1, su.queryForObject("select count(*) from audit_log where action = 'order.expire' and target_id = ?", Integer.class, old.toString()), "audited once, however often the job runs");
        assertEquals(0, su.queryForObject("select count(*) from audit_log where target_id = ?", Integer.class, confirmed.toString()));
    }
}
