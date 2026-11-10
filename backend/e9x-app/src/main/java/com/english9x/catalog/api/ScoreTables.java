package com.english9x.catalog.api;

import java.util.Optional;
import java.util.UUID;

/** Finds the score table to convert a result with. */
public interface ScoreTables {

    /**
     * The PUBLISHED table set for the TEST that the version belongs to (the override is keyed by the test, so a new version of the test
     * keeps it; highest version number), else the exam default. Since P2.6 a TEST-scope row exists only for a real test of the same owner, written by
     * the administrator of that owner, and {@code internal.TestLookup} looks the version's test up in {@code test_versions}. Deterministic:
     * the same state of the database gives the same table. Sees only rows the bound context may read (Row-Level Security), so a table of
     * another organisation is never found. Call it inside a transaction with the context bound (a request, or the grader's binding).
     *
     * @throws ScoreTableNotFoundException when neither exists
     * @throws IllegalArgumentException when the exam keeps no score under {@code skill}
     */
    ResolvedScoreTable resolve(String examCode, String skill, Optional<UUID> testVersionId);
}
