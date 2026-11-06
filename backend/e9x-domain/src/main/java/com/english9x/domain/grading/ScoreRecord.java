package com.english9x.domain.grading;

import java.time.Instant;

/** A score produced by {@code AI} or {@code TEACHER}. */
public record ScoreRecord(String source, double value, Instant at) {

    public ScoreRecord {
        if (!"AI".equals(source) && !"TEACHER".equals(source)) {
            throw new IllegalArgumentException("source must be AI or TEACHER: " + source);
        }
        if (Double.isNaN(value) || at == null) {
            throw new IllegalArgumentException("value and at are required");
        }
    }
}
