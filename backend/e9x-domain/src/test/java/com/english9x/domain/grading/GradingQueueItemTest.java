package com.english9x.domain.grading;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.time.Duration;
import java.time.Instant;
import java.util.Optional;
import org.junit.jupiter.api.Test;

public class GradingQueueItemTest {

    private static final Instant T0 = Instant.parse("2026-11-06T00:00:00Z");
    private static final Duration TIMEOUT = Duration.ofHours(24);

    @Test
    public void claimThenComplete() {
        GradingQueueItem item = new GradingQueueItem("q1");
        assertEquals(QueueItemStatus.OPEN, item.status());
        assertEquals(Optional.empty(), item.claimedBy());
        item.claim("t1", T0);
        assertEquals(QueueItemStatus.CLAIMED, item.status());
        assertEquals(Optional.of("t1"), item.claimedBy());
        assertEquals(Optional.of(T0), item.claimedAt());
        item.complete("t1");
        assertEquals(QueueItemStatus.DONE, item.status());
    }

    @Test
    public void cannotClaimTwice() {
        GradingQueueItem item = new GradingQueueItem("q1");
        item.claim("t1", T0);
        assertThrows(IllegalStateException.class, () -> item.claim("t2", T0));
    }

    @Test
    public void onlyClaimerCompletes() {
        GradingQueueItem item = new GradingQueueItem("q1");
        assertThrows(IllegalStateException.class, () -> item.complete("t1"));
        item.claim("t1", T0);
        assertThrows(IllegalStateException.class, () -> item.complete("t2"));
        assertEquals(QueueItemStatus.CLAIMED, item.status());
    }

    @Test
    public void releaseAfterTimeout() {
        GradingQueueItem item = new GradingQueueItem("q1");
        item.claim("t1", T0);
        assertTrue(item.releaseIfExpired(T0.plus(TIMEOUT), TIMEOUT));
        assertEquals(QueueItemStatus.OPEN, item.status());
        assertEquals(Optional.empty(), item.claimedBy());
        assertEquals(Optional.empty(), item.claimedAt());
        item.claim("t2", T0.plus(TIMEOUT));
        assertEquals(Optional.of("t2"), item.claimedBy());
    }

    @Test
    public void notReleasedBeforeTimeout() {
        GradingQueueItem item = new GradingQueueItem("q1");
        item.claim("t1", T0);
        assertFalse(item.releaseIfExpired(T0.plus(TIMEOUT).minusSeconds(1), TIMEOUT));
        assertEquals(QueueItemStatus.CLAIMED, item.status());
        assertFalse(new GradingQueueItem("q2").releaseIfExpired(T0.plus(TIMEOUT), TIMEOUT));
    }

    @Test
    public void reassignByAdmin() {
        GradingQueueItem item = new GradingQueueItem("q1");
        item.reassign("t1", T0);
        assertEquals(Optional.of("t1"), item.claimedBy());
        Instant later = T0.plusSeconds(60);
        item.reassign("t2", later);
        assertEquals(QueueItemStatus.CLAIMED, item.status());
        assertEquals(Optional.of("t2"), item.claimedBy());
        assertEquals(Optional.of(later), item.claimedAt());
        item.complete("t2");
        assertThrows(IllegalStateException.class, () -> item.reassign("t3", later));
    }
}
