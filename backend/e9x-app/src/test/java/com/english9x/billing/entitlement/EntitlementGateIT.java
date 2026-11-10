package com.english9x.billing.entitlement;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.english9x.billing.BillingIT;
import com.english9x.billing.api.Entitlements;
import com.english9x.org.api.CurrentContextHolder;
import java.sql.Timestamp;
import java.time.Duration;
import java.time.Instant;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

class EntitlementGateIT extends BillingIT {

    @Autowired Entitlements gate;

    private void entitle(UUID u, UUID c, Instant from, Instant until) {
        su.update("insert into entitlements(user_id,course_id,valid_from,valid_until,source_order_id) values (?,?,?,?,gen_random_uuid())", u, c, Timestamp.from(from), Timestamp.from(until));
    }

    private <T> T as(UUID u, java.util.function.Supplier<T> s) {
        try (CurrentContextHolder.Scope x = CurrentContextHolder.bind(u, null)) {
            return s.get();
        }
    }

    @Test
    void expiredDeniesNewLessonButAllowsHistory() {
        UUID u = user("exp");
        UUID c = course("PUBLISHED");
        Instant now = Instant.now();
        entitle(u, c, now.minus(Duration.ofDays(60)), now.minus(Duration.ofDays(1)));
        assertFalse(as(u, () -> gate.activeFor(u, c, now)), "no new lesson");
        assertTrue(as(u, () -> gate.activeFor(u, c, now.minus(Duration.ofDays(10)))), "it was active then");
        Res mine = get("/api/me/entitlements", u, null);
        assertEquals(200, mine.status());
        assertEquals(1, mine.body().size(), "the expired plan is still listed: history stays");
        assertFalse(mine.body().get(0).path("active").asBoolean());
    }

    @Test
    void attemptInProgressSurvivesExpiry() {
        UUID u = user("mid");
        UUID c = course("PUBLISHED");
        Instant now = Instant.now();
        entitle(u, c, now.minus(Duration.ofDays(30)), now.minus(Duration.ofMinutes(5)));
        Instant startedBefore = now.minus(Duration.ofMinutes(50));
        assertTrue(as(u, () -> gate.canContinueAttempt(u, c, startedBefore)), "finishes the attempt it began while entitled");
        assertFalse(as(u, () -> gate.activeFor(u, c, now)), "but no new lesson");
        assertFalse(as(u, () -> gate.canContinueAttempt(u, c, now.minus(Duration.ofDays(40)))), "an attempt begun before any entitlement cannot go on");
    }

    @Test
    void boundariesAndStrangersAndNobody() {
        UUID u = user("b");
        UUID other = user("o");
        UUID c = course("PUBLISHED");
        Instant from = Instant.now().minus(Duration.ofDays(1));
        Instant until = from.plus(Duration.ofDays(30));
        entitle(u, c, from, until);
        assertTrue(as(u, () -> gate.activeFor(u, c, from)), "valid from is inclusive");
        assertFalse(as(u, () -> gate.activeFor(u, c, until)), "valid until is exclusive");
        assertFalse(as(u, () -> gate.activeFor(u, c, from.minusSeconds(1))));
        assertFalse(as(other, () -> gate.activeFor(u, c, from.plusSeconds(5))), "another person's entitlement is invisible");
        assertFalse(gate.activeFor(u, c, from.plusSeconds(5)), "nobody bound: closed");
        assertFalse(as(u, () -> gate.activeFor(u, UUID.randomUUID(), from.plusSeconds(5))), "another course");
    }
}
