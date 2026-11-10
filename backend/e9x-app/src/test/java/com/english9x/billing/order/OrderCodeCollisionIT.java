package com.english9x.billing.order;

import static org.junit.jupiter.api.Assertions.assertEquals;

import com.english9x.billing.BillingIT;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.context.annotation.Primary;

/** The generator hands out a taken code first: the unique index refuses it and the service tries again. */
@Import(OrderCodeCollisionIT.Scripted.class)
class OrderCodeCollisionIT extends BillingIT {

    static final String TAKEN = "AAAAAA";

    @TestConfiguration
    static class Scripted {
        @Bean
        @Primary
        OrderCodes scripted() {
            AtomicInteger n = new AtomicInteger();
            return () -> n.getAndIncrement() % 3 == 2 ? "BBB" + (100 + n.get() % 900) : TAKEN;
        }
    }

    @Test
    void aTakenCodeIsRetried() {
        UUID owner = user("first");
        UUID plan = plan(100_000, 1, 1, true);
        UUID c = course("PUBLISHED");
        su.update("insert into orders(code,user_id,plan_id,plan_months,amount_vnd) values (?,?,?,1,1000)", TAKEN, owner, plan);
        Res r = post("/api/orders", Map.of("planId", plan, "courseIds", List.of(c)), user("second"), null);
        assertEquals(201, r.status(), r.raw());
        assertEquals(true, r.body().path("orderCode").asText().startsWith("BBB"));
    }
}
