package com.english9x.domain.billing;

import java.time.Instant;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.List;

public final class Entitlements {
    private Entitlements() {}

    public static Instant validUntil(Instant confirmedAt, int months, ZoneId zone) {
        return confirmedAt.atZone(zone).plusMonths(months).toInstant();
    }

    public static List<Entitlement> forConfirmedOrder(Order order, Plan plan, ZoneId zone) {
        if (order.status() != OrderStatus.CONFIRMED) {
            throw new IllegalStateException("order " + order.code() + " is not confirmed");
        }
        Instant from = order.confirmedAt().orElseThrow();
        Instant until = validUntil(from, plan.months(), zone);
        List<Entitlement> result = new ArrayList<>();
        for (String courseId : order.courseIds()) {
            result.add(new Entitlement(order.userId(), courseId, from, until, EntitlementSource.ORDER, order.code()));
        }
        return List.copyOf(result);
    }
}
