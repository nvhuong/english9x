package com.english9x.domain.learning;

import java.time.Duration;
import java.time.Instant;
import java.util.List;

public final class StudyTime {
    private StudyTime() {
    }

    /** Sums gaps between consecutive heartbeats that are at most {@code idleTimeout}. */
    public static Duration credited(List<Instant> heartbeats, Duration idleTimeout) {
        if (heartbeats == null || idleTimeout == null || idleTimeout.isNegative()) {
            throw new IllegalArgumentException("invalid study time arguments");
        }
        List<Instant> sorted = heartbeats.stream().sorted().toList();
        Duration total = Duration.ZERO;
        for (int i = 1; i < sorted.size(); i++) {
            Duration gap = Duration.between(sorted.get(i - 1), sorted.get(i));
            if (gap.compareTo(idleTimeout) <= 0) {
                total = total.plus(gap);
            }
        }
        return total;
    }
}
