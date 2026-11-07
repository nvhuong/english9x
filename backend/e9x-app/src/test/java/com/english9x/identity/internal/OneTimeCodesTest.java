package com.english9x.identity.internal;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.english9x.support.MutableClock;
import java.time.Duration;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class OneTimeCodesTest {

    private final MutableClock clock = new MutableClock(java.time.Instant.parse("2026-11-07T00:00:00Z"));

    private OneTimeCodes codes() {
        return new OneTimeCodes(clock, new AuthProperties("0123456789abcdef0123456789abcdef", "english9x", Duration.ofMinutes(15),
                Duration.ofDays(30), Duration.ofSeconds(10), "10/15m", "3/1h", "5/1h", "20/1h", Duration.ofHours(24),
                Duration.ofHours(1), Duration.ofSeconds(60), 4, 8, java.util.List.of(), "x <x@x.test>", null));
    }

    @Test
    void aCodeIsRedeemableOnce() {
        OneTimeCodes c = codes();
        UUID id = UUID.randomUUID();
        String code = c.issue(id, 7);
        assertEquals(new OneTimeCodes.Redeemed(id, 7), c.redeem(code).orElseThrow());
        assertTrue(c.redeem(code).isEmpty(), "second use");
    }

    @Test
    void aCodeExpiresAfterItsTtl() {
        OneTimeCodes c = codes();
        String code = c.issue(UUID.randomUUID(), 1);
        clock.advance(Duration.ofSeconds(61));
        assertTrue(c.redeem(code).isEmpty());
    }

    @Test
    void guessesAndGarbageFail() {
        OneTimeCodes c = codes();
        c.issue(UUID.randomUUID(), 1);
        assertTrue(c.redeem("A".repeat(43)).isEmpty());
        assertTrue(c.redeem("x").isEmpty());
        assertTrue(c.redeem(null).isEmpty());
    }
}
