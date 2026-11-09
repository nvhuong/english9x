package com.english9x.catalog.api;

import com.english9x.domain.scoring.ScoreConverter;
import java.util.Optional;
import java.util.UUID;

/**
 * The score table a result is converted with. {@code scoreTableVersionId} is what the result records, so editing the table later does
 * not change old results. {@code converter} is present for {@link ScoreFunction#TABLE}; for the other functions the caller applies the
 * domain rule the function names. {@code testOverride} says the table was set for the test, not the exam default.
 */
public record ResolvedScoreTable(
        UUID scoreTableVersionId,
        String examCode,
        String skill,
        ScoreFunction function,
        Optional<ScoreConverter> converter,
        boolean testOverride) {}
