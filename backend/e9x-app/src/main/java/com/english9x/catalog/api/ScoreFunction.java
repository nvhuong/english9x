package com.english9x.catalog.api;

/** How a score is produced (spec 10.2). Only {@link #TABLE} converts a single raw number; the others combine section results with the domain's own rules. */
public enum ScoreFunction {
    /** raw correct answers to a scaled score through a lookup table. */
    TABLE,
    /** IELTS overall band from the four skills ({@code Ielts.overall}). */
    IELTS_OVERALL,
    /** IELTS writing band from the two tasks ({@code Ielts.writingBand}). */
    IELTS_WRITING_WEIGHTED,
    /** TOEIC total from Listening and Reading ({@code Toeic.total}). */
    SUM
}
