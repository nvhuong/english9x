package com.english9x.domain.grading;

import java.time.Duration;
import java.time.Instant;
import java.util.Optional;

/** A submission waiting for teacher grading: OPEN -> CLAIMED -> DONE, with timeout release. */
public final class GradingQueueItem {

    private final String id;
    private QueueItemStatus status = QueueItemStatus.OPEN;
    private String claimedBy;
    private Instant claimedAt;

    public GradingQueueItem(String id) {
        if (id == null || id.isBlank()) {
            throw new IllegalArgumentException("id must not be blank");
        }
        this.id = id;
    }

    public String id() {
        return id;
    }

    public void claim(String teacherId, Instant now) {
        requireTeacher(teacherId);
        requireNow(now);
        if (status != QueueItemStatus.OPEN) {
            throw new IllegalStateException("item is " + status + ", only OPEN items can be claimed");
        }
        assign(teacherId, now);
    }

    public void complete(String teacherId) {
        requireTeacher(teacherId);
        if (status != QueueItemStatus.CLAIMED) {
            throw new IllegalStateException("item is " + status + ", only CLAIMED items can be completed");
        }
        if (!claimedBy.equals(teacherId)) {
            throw new IllegalStateException("item is claimed by another teacher");
        }
        status = QueueItemStatus.DONE;
    }

    /** Returns a stale claim to OPEN when {@code now >= claimedAt + timeout}; true if released. */
    public boolean releaseIfExpired(Instant now, Duration timeout) {
        requireNow(now);
        if (timeout == null || timeout.isNegative()) {
            throw new IllegalArgumentException("timeout must not be negative");
        }
        if (status != QueueItemStatus.CLAIMED || now.isBefore(claimedAt.plus(timeout))) {
            return false;
        }
        status = QueueItemStatus.OPEN;
        claimedBy = null;
        claimedAt = null;
        return true;
    }

    public void reassign(String teacherId, Instant now) {
        requireTeacher(teacherId);
        requireNow(now);
        if (status == QueueItemStatus.DONE) {
            throw new IllegalStateException("item is DONE and cannot be reassigned");
        }
        assign(teacherId, now);
    }

    public QueueItemStatus status() {
        return status;
    }

    public Optional<String> claimedBy() {
        return Optional.ofNullable(claimedBy);
    }

    public Optional<Instant> claimedAt() {
        return Optional.ofNullable(claimedAt);
    }

    private void assign(String teacherId, Instant now) {
        status = QueueItemStatus.CLAIMED;
        claimedBy = teacherId;
        claimedAt = now;
    }

    private static void requireTeacher(String teacherId) {
        if (teacherId == null || teacherId.isBlank()) {
            throw new IllegalArgumentException("teacherId must not be blank");
        }
    }

    private static void requireNow(Instant now) {
        if (now == null) {
            throw new IllegalArgumentException("now must not be null");
        }
    }
}
