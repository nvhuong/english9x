package com.english9x.domain.learning;

/** Learner progress on one lesson; {@code bestScore} is in [0, 1] or null. */
public record LessonStatus(boolean completed, Double bestScore) {
    public LessonStatus {
        if (bestScore != null && !(bestScore >= 0.0 && bestScore <= 1.0)) {
            throw new IllegalArgumentException("bestScore must be within [0, 1]");
        }
    }
}
