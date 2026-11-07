package com.english9x.catalog.api;

import java.util.Optional;
import java.util.UUID;

/** Finds the score table to convert a result with. */
public interface ScoreTables {

    /**
     * The PUBLISHED table for the test version if one was set for it (highest version number), else the exam default. Deterministic:
     * the same state of the database gives the same table. Sees only rows the bound context may read (Row-Level Security), so a table of
     * another organisation is never found. Call it inside a transaction with the context bound (a request, or the grader's binding).
     *
     * @throws ScoreTableNotFoundException when neither exists
     * @throws IllegalArgumentException when the exam keeps no score under {@code skill}
     */
    ResolvedScoreTable resolve(String examCode, String skill, Optional<UUID> testVersionId);
}
