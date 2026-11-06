package com.english9x.domain.billing;

import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

import java.time.Instant;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.util.List;

public class EntitlementsTest {
    private static final ZoneId HCM = ZoneId.of("Asia/Ho_Chi_Minh");

    @Test
    public void validUntilAddsCalendarMonths() {
        Instant at = ZonedDateTime.of(2027, 1, 31, 10, 0, 0, 0, HCM).toInstant();
        Instant expected = ZonedDateTime.of(2027, 2, 28, 10, 0, 0, 0, HCM).toInstant();
        assertEquals(expected, Entitlements.validUntil(at, 1, HCM));
    }

    @Test
    public void confirmedOrderCreatesOnePerCourse() {
        Instant at = Instant.parse("2026-11-06T03:00:00Z");
        Order o = new Order("D2611A", "u1", 300000, List.of("c1", "c2"), at.minusSeconds(100));
        o.confirm(at, "FT1");
        List<Entitlement> ents = Entitlements.forConfirmedOrder(o, new Plan("p", 300000, 6, 2), HCM);
        assertEquals(2, ents.size());
        assertEquals("c1", ents.get(0).courseId());
        assertEquals("c2", ents.get(1).courseId());
        for (Entitlement e : ents) {
            assertEquals("u1", e.userId());
            assertEquals(at, e.validFrom());
            assertEquals(Entitlements.validUntil(at, 6, HCM), e.validUntil());
            assertEquals(EntitlementSource.ORDER, e.source());
            assertEquals("D2611A", e.sourceId());
        }
    }

    @Test
    public void pendingOrderCannotCreateEntitlements() {
        Order o = new Order("D2611A", "u1", 1, List.of("c1"), Instant.parse("2026-11-06T00:00:00Z"));
        assertThrows(IllegalStateException.class, () -> Entitlements.forConfirmedOrder(o, new Plan("p", 1, 1, 1), HCM));
    }
}
