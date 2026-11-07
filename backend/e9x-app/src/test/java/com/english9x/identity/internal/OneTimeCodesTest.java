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
                Duration.ofHours(1), Duration.ofSeconds(60), Duration.ofMinutes(15), 4, 8, java.util.List.of(), "x <x@x.test>", null));
    }

    @Test
    void aCodeIsRedeemableOnceInItsBrowser() {
        OneTimeCodes c = codes();
        UUID id = UUID.randomUUID();
        OneTimeCodes.Issued i = c.issue(id, 7);
        OneTimeCodes.Redeemed who = c.redeem(i.code(), i.browserKey()).orElseThrow();
        assertEquals(id, who.userId());
        assertEquals(7, who.credentialsVersion());
        assertTrue(c.redeem(i.code(), i.browserKey()).isEmpty(), "second use");
    }

    @Test
    void anotherBrowserGetsNothingAndBurnsNothing() {
        OneTimeCodes c = codes();
        OneTimeCodes.Issued mine = c.issue(UUID.randomUUID(), 1);
        OneTimeCodes.Issued theirs = c.issue(UUID.randomUUID(), 1);
        assertTrue(c.redeem(mine.code(), theirs.browserKey()).isEmpty());
        assertTrue(c.redeem(mine.code(), null).isEmpty());
        assertTrue(c.redeem(mine.code(), "x").isEmpty());
        assertTrue(c.redeem(mine.code(), mine.browserKey()).isPresent(), "the rightful browser still can");
    }

    @Test
    void aCodeExpiresAfterItsTtl() {
        OneTimeCodes c = codes();
        OneTimeCodes.Issued i = c.issue(UUID.randomUUID(), 1);
        clock.advance(Duration.ofSeconds(61));
        assertTrue(c.redeem(i.code(), i.browserKey()).isEmpty());
    }

    @Test
    void aCodeHeldForConsentOutlivesItsTtlButNotTheConsentWindowEvenIfHeldAgain() {
        OneTimeCodes c = codes();
        OneTimeCodes.Issued i = c.issue(UUID.randomUUID(), 1);
        OneTimeCodes.Redeemed who = c.redeem(i.code(), i.browserKey()).orElseThrow();
        c.holdForConsent(i.code(), i.browserKey(), who);
        clock.advance(Duration.ofMinutes(14));
        who = c.redeem(i.code(), i.browserKey()).orElseThrow();
        c.holdForConsent(i.code(), i.browserKey(), who); // asked again: the window is counted from the issue
        clock.advance(Duration.ofMinutes(2));
        assertTrue(c.redeem(i.code(), i.browserKey()).isEmpty());
        c.holdForConsent(i.code(), i.browserKey(), who); // past the window: not even put back
        assertTrue(c.redeem(i.code(), i.browserKey()).isEmpty());
    }

    @Test
    void guessesAndGarbageFail() {
        OneTimeCodes c = codes();
        OneTimeCodes.Issued i = c.issue(UUID.randomUUID(), 1);
        assertTrue(c.redeem("A".repeat(43), i.browserKey()).isEmpty());
        assertTrue(c.redeem("x", i.browserKey()).isEmpty());
        assertTrue(c.redeem(null, i.browserKey()).isEmpty());
    }
}
