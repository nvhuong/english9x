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
            assertTrue(l.tryAcquire("k"));
        }
        assertFalse(l.tryAcquire("k"));
        assertTrue(l.tryAcquire("other"));
        assertTrue(l.retryAfterSeconds("k") > 0 && l.retryAfterSeconds("k") <= 15 * 60 + 1);
        clock.advance(Duration.ofMinutes(15).plusSeconds(1));
        assertTrue(l.tryAcquire("k"), "attempts leave the window");
    }

    @Test
    void slidingWindowOnlyCountsRecentAttempts() {
        AttemptLimiter l = new AttemptLimiter("2/10m", clock);
        assertTrue(l.tryAcquire("k"));
        clock.advance(Duration.ofMinutes(6));
        assertTrue(l.tryAcquire("k"));
        assertFalse(l.tryAcquire("k"));
        clock.advance(Duration.ofMinutes(5)); // the first attempt is now 11 minutes old
        assertTrue(l.tryAcquire("k"));
    }

    @Test
    void resetGivesTheAllowanceBack() {
        AttemptLimiter l = new AttemptLimiter("1/10m", clock);
        assertTrue(l.tryAcquire("k"));
        assertFalse(l.tryAcquire("k"));
        l.reset("k");
        assertTrue(l.tryAcquire("k"));
    }

    @Test
    void aRefusedAttemptDoesNotExtendTheBlock() {
        AttemptLimiter l = new AttemptLimiter("1/10m", clock);
        assertTrue(l.tryAcquire("k"));
        clock.advance(Duration.ofMinutes(9));
        assertFalse(l.tryAcquire("k"));
        clock.advance(Duration.ofMinutes(2));
        assertTrue(l.tryAcquire("k"), "counted from the attempt that was accepted");
    }

    @Test
    void exactlyTheLimitIsGrantedUnderContention() throws Exception {
        for (int round = 0; round < 20; round++) {
            AttemptLimiter l = new AttemptLimiter("10/15m", clock);
            int threads = 32;
            java.util.concurrent.CyclicBarrier go = new java.util.concurrent.CyclicBarrier(threads);
            java.util.concurrent.ExecutorService pool = java.util.concurrent.Executors.newFixedThreadPool(threads);
            java.util.List<java.util.concurrent.Future<Boolean>> all = new java.util.ArrayList<>();
            for (int i = 0; i < threads; i++) {
                all.add(pool.submit(() -> {
                    go.await();
                    return l.tryAcquire("k");
                }));
            }
            int granted = 0;
            for (java.util.concurrent.Future<Boolean> f : all) {
                granted += f.get() ? 1 : 0;
            }
            pool.shutdownNow();
            assertEquals(10, granted, "round " + round);
        }
    }

    @Test
    void memoryIsBoundedWhateverTheAttackerSends() {
        AttemptLimiter l = new AttemptLimiter("1/10m", clock, 100);
        for (int i = 0; i < 10_000; i++) {
            l.tryAcquire("a" + i + "@x");
        }
        assertTrue(l.size() <= 100, "size " + l.size());
    }

    @Test
    void rejectsABadSpecification() {
        assertThrows(IllegalArgumentException.class, () -> new AttemptLimiter("ten per minute", clock));
        assertThrows(IllegalArgumentException.class, () -> new AttemptLimiter("10/15", clock));
    }
}
