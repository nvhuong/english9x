package com.english9x.domain.learning;

import java.util.List;
import java.util.Optional;

public final class ChapterTestAttempts {
    private ChapterTestAttempts() {
    }

    /** {@code maxAttempts == 0} means unlimited. */
    public static boolean canRetake(int used, int maxAttempts) {
        if (used < 0 || maxAttempts < 0) {
            throw new IllegalArgumentException("used and maxAttempts must be >= 0");
        }
        return maxAttempts == 0 || used < maxAttempts;
    }

    public static Optional<Double> best(List<Double> scores) {
        if (scores == null) {
            throw new IllegalArgumentException("scores must not be null");
        }
        return scores.stream().max(Double::compare);
    }
}
