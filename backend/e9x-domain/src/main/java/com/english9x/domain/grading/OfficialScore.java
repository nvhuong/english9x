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
        return switch (mode) {
            case AI -> ai.map(s -> new DisplayedScore(s, ScoreKind.OFFICIAL));
            case TEACHER -> teacher.map(s -> new DisplayedScore(s, ScoreKind.OFFICIAL));
            case BOTH -> teacher.map(s -> new DisplayedScore(s, ScoreKind.OFFICIAL))
                    .or(() -> ai.map(s -> new DisplayedScore(s, ScoreKind.PRELIMINARY)));
        };
    }
}
