package com.english9x.platform.jobs;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;
import org.springframework.boot.actuate.health.Status;
import org.springframework.mock.env.MockEnvironment;

/** The polling loop and its health indicator, without a database. */
class JobWorkerResilienceTest {

    static final JobsProperties FAST = new JobsProperties(Duration.ofMillis(10), 1, Duration.ofMinutes(10), Duration.ZERO, List.of());

    /** A store whose claim throws an Error (not an Exception) every time. */
    static class ExplodingStore extends JobStore {
        final AtomicInteger calls = new AtomicInteger();

        ExplodingStore() {
            super(null, new ObjectMapper(), Duration.ofMinutes(10));
        }

        @Override
        Optional<ClaimedJob> claim(Instant now) {
            calls.incrementAndGet();
            throw new AssertionError("boom from the store");
        }

        @Override
        Optional<ClaimedJob> claim(Instant now, Set<String> onlyTypes) {
            return claim(now);
        }
    }

    @Test
    void anErrorInThePollLoopIsLoggedAndTheThreadKeepsPolling() throws Exception {
        ExplodingStore store = new ExplodingStore();
        JobWorker w = new JobWorker(store, null, new RetryPolicy(new MockEnvironment()), null, FAST, Clock.systemUTC(), List.of());
        w.start();
        try {
            long end = System.currentTimeMillis() + 10_000;
            while (store.calls.get() < 5 && System.currentTimeMillis() < end) {
                Thread.sleep(10);
            }
            assertTrue(store.calls.get() >= 5, "polled repeatedly after errors: " + store.calls.get());
            assertEquals(1, w.liveThreads());
            assertTrue(w.isRunning());
        } finally {
            w.stop();
        }
    }

    @Test
    void healthIsDownOnlyWhenARunningWorkerHasLostItsThreads() {
        JobWorker lost = new JobWorker(null, null, null, null, FAST, Clock.systemUTC(), List.of()) {
            @Override
            public boolean isRunning() {
                return true;
            }

            @Override
            int liveThreads() {
                return 0;
            }
        };
        assertEquals(Status.DOWN, new JobWorkerHealthIndicator(lost).health().getStatus());

        JobWorker fine = new JobWorker(null, null, null, null, FAST, Clock.systemUTC(), List.of()) {
            @Override
            public boolean isRunning() {
                return true;
            }

            @Override
            int liveThreads() {
                return 1;
            }
        };
        assertEquals(Status.UP, new JobWorkerHealthIndicator(fine).health().getStatus());
    }
}
