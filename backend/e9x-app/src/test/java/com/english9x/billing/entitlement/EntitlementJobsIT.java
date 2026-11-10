package com.english9x.billing.entitlement;

import static org.junit.jupiter.api.Assertions.assertEquals;

import com.english9x.billing.BillingIT;
import com.english9x.platform.jobs.api.RecurringJob;
import java.sql.Timestamp;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

class EntitlementJobsIT extends BillingIT {

    @Autowired List<RecurringJob> schedules;

    private UUID entitle(UUID u, Instant until, boolean order) {
        UUID id = UUID.randomUUID();
        UUID c = course("PUBLISHED");
        su.update("insert into entitlements(id,user_id,course_id,valid_from,valid_until,source_order_id,source_seat_id,source_org_id) values (?,?,?,?,?,?,?,?)",
                id, u, c, Timestamp.from(until.minus(Duration.ofDays(60))), Timestamp.from(until), order ? UUID.randomUUID() : null,
                order ? null : UUID.randomUUID(), order ? null : org("CENTER"));
        return id;
    }

    private int notices(UUID u, String type) {
        return su.queryForObject("select count(*) from notifications where user_id = ? and event_type = ?", Integer.class, u, type);
    }

    @Test
    void theThreeBillingJobsAreScheduled() {
        Set<String> types = schedules.stream().filter(s -> s.type().startsWith("expire_") || s.type().startsWith("plan_")).map(RecurringJob::type).collect(Collectors.toSet());
        assertEquals(Set.of("expire_orders", "expire_entitlements", "plan_expiry_reminder"), types);
    }

    @Test
    void reminderIsSentOncePerExpiryNotEveryRun() {
        UUID u = user("rem");
        Instant now = clock.instant();
        entitle(u, now.plus(Duration.ofDays(3)), true);
        entitle(u, now.plus(Duration.ofDays(30)), true);
        entitle(user("seat"), now.plus(Duration.ofDays(3)), false);
        runJob("plan_expiry_reminder");
        runJob("plan_expiry_reminder");
        runJob("plan_expiry_reminder");
        assertEquals(1, notices(u, "PLAN_EXPIRING"), "one reminder for the one plan ending within 7 days");
        assertEquals(0, notices(u, "PLAN_EXPIRED"));
    }

    @Test
    void expiredNoticeOnceAndOnlyWithinTheWindow() {
        UUID u = user("expd");
        Instant now = clock.instant();
        entitle(u, now.minus(Duration.ofHours(2)), true);
        entitle(u, now.minus(Duration.ofDays(40)), true);
        entitle(u, now.plus(Duration.ofDays(2)), true);
        runJob("expire_entitlements");
        runJob("expire_entitlements");
        assertEquals(1, notices(u, "PLAN_EXPIRED"));
    }
}
