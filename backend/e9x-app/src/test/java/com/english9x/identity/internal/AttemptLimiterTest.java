package com.english9x.identity.internal;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.english9x.support.MutableClock;
import java.time.Duration;
import org.junit.jupiter.api.Test;

class AttemptLimiterTest {

    private final MutableClock clock = new MutableClock(java.time.Instant.parse("2026-11-07T00:00:00Z"));

    @Test
    void blocksWhenTheCountIsReachedAndFreesAfterTheWindow() {
        AttemptLimiter l = new AttemptLimiter("3/15m", clock);
        for (int i = 0; i < 3; i++) {
            assertFalse(l.blocked("k"));
            l.recordFailure("k");
        }
        assertTrue(l.blocked("k"));
        assertFalse(l.blocked("other"));
        assertTrue(l.retryAfterSeconds("k") > 0 && l.retryAfterSeconds("k") <= 15 * 60 + 1);
        clock.advance(Duration.ofMinutes(15).plusSeconds(1));
        assertFalse(l.blocked("k"), "failures leave the window");
        assertEquals(0, l.size(), "and the key is dropped");
    }

    @Test
    void slidingWindowOnlyCountsRecentFailures() {
        AttemptLimiter l = new AttemptLimiter("2/10m", clock);
        l.recordFailure("k");
        clock.advance(Duration.ofMinutes(6));
        l.recordFailure("k");
        assertTrue(l.blocked("k"));
        clock.advance(Duration.ofMinutes(5)); // the first failure is now 11 minutes old
        assertFalse(l.blocked("k"));
    }

    @Test
    void resetClearsTheKey() {
        AttemptLimiter l = new AttemptLimiter("1/10m", clock);
        l.recordFailure("k");
        assertTrue(l.blocked("k"));
        l.reset("k");
        assertFalse(l.blocked("k"));
    }

    @Test
    void memoryIsBoundedWhateverTheAttackerSends() {
        AttemptLimiter l = new AttemptLimiter("1/10m", clock, 100);
        for (int i = 0; i < 10_000; i++) {
            l.recordFailure("a" + i + "@x");
        }
        assertTrue(l.size() <= 100, "size " + l.size());
    }

    @Test
    void rejectsABadSpecification() {
        assertThrows(IllegalArgumentException.class, () -> new AttemptLimiter("ten per minute", clock));
        assertThrows(IllegalArgumentException.class, () -> new AttemptLimiter("10/15", clock));
    }
}
