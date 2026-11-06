package com.english9x.domain.grading;

import java.util.Optional;

public final class OfficialScore {

    private OfficialScore() {
    }

    public static Optional<DisplayedScore> select(GradingMode mode, Optional<ScoreRecord> ai,
            Optional<ScoreRecord> teacher) {
        if (mode == null || ai == null || teacher == null) {
            throw new IllegalArgumentException("arguments must not be null");
        }
        // A teacher score, when present, is OFFICIAL in every mode.
        if (teacher.isPresent()) {
            return teacher.map(s -> new DisplayedScore(s, ScoreKind.OFFICIAL));
        }
        return switch (mode) {
            case AI -> ai.map(s -> new DisplayedScore(s, ScoreKind.OFFICIAL));
            case BOTH -> ai.map(s -> new DisplayedScore(s, ScoreKind.PRELIMINARY));
            case TEACHER -> Optional.empty();
        };
    }
}
