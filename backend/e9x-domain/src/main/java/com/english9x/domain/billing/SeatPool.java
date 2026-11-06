package com.english9x.domain.billing;

import java.util.HashSet;
import java.util.Set;

public final class SeatPool {
    private final int seats;
    private final Set<String> assigned = new HashSet<>();

    public SeatPool(int seats) {
        if (seats < 0) throw new IllegalArgumentException("seats must be >= 0");
        this.seats = seats;
    }

    public void assign(String userId) {
        if (assigned.contains(userId)) throw new IllegalStateException("already assigned: " + userId);
        if (assigned.size() >= seats) throw new IllegalStateException("no seats available");
        assigned.add(userId);
    }

    public void revoke(String userId) {
        if (!assigned.remove(userId)) throw new IllegalStateException("not assigned: " + userId);
    }

    public int available() { return seats - assigned.size(); }

    public boolean isAssigned(String userId) { return assigned.contains(userId); }
}
