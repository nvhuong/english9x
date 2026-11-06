package com.english9x.domain.billing;

import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

import java.time.Duration;
import java.time.Instant;
import java.util.List;

public class OrderTest {
    private static final Instant T0 = Instant.parse("2026-11-06T00:00:00Z");

    private static Order order() { return new Order("D2611A", "u1", 150000, List.of("c1"), T0); }

    @Test
    public void confirmFromPending() {
        Order o = order();
        assertEquals(OrderStatus.PENDING_PAYMENT, o.status());
        o.confirm(T0.plusSeconds(60), "FT123");
        assertEquals(OrderStatus.CONFIRMED, o.status());
        assertEquals(T0.plusSeconds(60), o.confirmedAt().get());
        assertEquals("FT123", o.bankRef().get());
    }

    @Test
    public void rejectRequiresReason() {
        Order o = order();
        assertThrows(IllegalArgumentException.class, () -> o.reject(T0, " "));
        o.reject(T0, "no money");
        assertEquals(OrderStatus.REJECTED, o.status());
        assertEquals("no money", o.rejectReason().get());
    }

    @Test
    public void cannotConfirmTwice() {
        Order o = order();
        o.confirm(T0, "a");
        assertThrows(IllegalStateException.class, () -> o.confirm(T0, "b"));
        assertThrows(IllegalStateException.class, () -> o.reject(T0, "x"));
    }

    @Test
    public void expiresAfterTtl() {
        Order o = order();
        assertTrue(o.expireIfDue(T0.plus(Duration.ofDays(7)), Duration.ofDays(7)));
        assertEquals(OrderStatus.EXPIRED, o.status());
    }

    @Test
    public void doesNotExpireBeforeTtl() {
        Order o = order();
        assertFalse(o.expireIfDue(T0.plus(Duration.ofDays(7)).minusSeconds(1), Duration.ofDays(7)));
        assertEquals(OrderStatus.PENDING_PAYMENT, o.status());
    }

    @Test
    public void confirmedOrderDoesNotExpire() {
        Order o = order();
        o.confirm(T0, "a");
        assertFalse(o.expireIfDue(T0.plus(Duration.ofDays(30)), Duration.ofDays(7)));
        assertEquals(OrderStatus.CONFIRMED, o.status());
    }
}
