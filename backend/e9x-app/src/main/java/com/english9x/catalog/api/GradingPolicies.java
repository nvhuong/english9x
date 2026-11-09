package com.english9x.catalog.api;

import com.english9x.domain.grading.GradingMode;
import java.util.Optional;
import java.util.UUID;

/** Who grades writing and speaking: AI, teachers or both. */
public interface GradingPolicies {

    /**
     * The mode by spec 10.3: the policy of the test the version belongs to (keyed by test, see {@code ScoreTables}; none before P2.6), else the organisation's, else the exam's, else the configured default
     * ({@code e9x.grading.default-mode}). Resolution is {@code GradingPolicyResolver}. Sees only rows the bound context may read: the
     * policy of an organisation other than the bound one is never found, so it cannot decide here.
     */
    GradingMode resolve(String examCode, Optional<UUID> orgId, Optional<UUID> testVersionId);
}
