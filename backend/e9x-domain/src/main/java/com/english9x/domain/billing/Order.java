package com.english9x.domain.billing;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Optional;

public final class Order {
    private final String code;
    private final String userId;
    private final long amountVnd;
    private final List<String> courseIds;
    private final Instant createdAt;
    private OrderStatus status = OrderStatus.PENDING_PAYMENT;
    private Instant confirmedAt;
    private String rejectReason;
    private String bankRef;

    public Order(String code, String userId, long amountVnd, List<String> courseIds, Instant createdAt) {
        if (code == null || userId == null || createdAt == null || courseIds == null) {
            throw new IllegalArgumentException("order fields must not be null");
        }
        if (amountVnd <= 0) {
            throw new IllegalArgumentException("amountVnd must be positive");
        }
        this.code = code;
        this.userId = userId;
        this.amountVnd = amountVnd;
        this.courseIds = List.copyOf(courseIds);
        this.createdAt = createdAt;
    }

    public void confirm(Instant now, String bankRef) {
        requirePending();
        this.status = OrderStatus.CONFIRMED;
        this.confirmedAt = now;
        this.bankRef = bankRef;
    }

    public void reject(Instant now, String reason) {
        if (reason == null || reason.isBlank()) {
            throw new IllegalArgumentException("reject reason required");
        }
        requirePending();
        this.status = OrderStatus.REJECTED;
        this.rejectReason = reason;
    }

    public boolean expireIfDue(Instant now, Duration ttl) {
        if (status != OrderStatus.PENDING_PAYMENT || now.isBefore(createdAt.plus(ttl))) {
            return false;
        }
        status = OrderStatus.EXPIRED;
        return true;
    }

    private void requirePending() {
        if (status != OrderStatus.PENDING_PAYMENT) {
            throw new IllegalStateException("order is " + status + ", not PENDING_PAYMENT");
        }
    }

    public String code() { return code; }
    public String userId() { return userId; }
    public long amountVnd() { return amountVnd; }
    public List<String> courseIds() { return courseIds; }
    public Instant createdAt() { return createdAt; }
    public OrderStatus status() { return status; }
    public Optional<Instant> confirmedAt() { return Optional.ofNullable(confirmedAt); }
    public Optional<String> rejectReason() { return Optional.ofNullable(rejectReason); }
    public Optional<String> bankRef() { return Optional.ofNullable(bankRef); }
}
