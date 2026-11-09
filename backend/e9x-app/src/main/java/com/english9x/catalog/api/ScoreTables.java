package com.english9x.catalog.api;

import java.util.Optional;
import java.util.UUID;

/** Finds the score table to convert a result with. */
public interface ScoreTables {

    /**
     * The PUBLISHED table set for the TEST that the version belongs to (the override is keyed by the test, so a new version of the test
     * keeps it; highest version number), else the exam default. Until P2.6 no test has an override (TEST-scope rows are refused by the
     * database) and {@code internal.TestLookup} is the one place P2.6 changes to look a version's test up. Deterministic:
     * the same state of the database gives the same table. Sees only rows the bound context may read (Row-Level Security), so a table of
     * another organisation is never found. Call it inside a transaction with the context bound (a request, or the grader's binding).
     *
     * @throws ScoreTableNotFoundException when neither exists
     * @throws IllegalArgumentException when the exam keeps no score under {@code skill}
     */
    ResolvedScoreTable resolve(String examCode, String skill, Optional<UUID> testVersionId);
}
