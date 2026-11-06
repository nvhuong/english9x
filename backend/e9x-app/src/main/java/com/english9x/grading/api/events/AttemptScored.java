package com.english9x.grading.api.events;

import java.util.Map;
import java.util.UUID;

/** An attempt received its score (preliminary or official), per skill. */
public record AttemptScored(UUID attemptId, UUID userId, Map<String, Double> skillScores, boolean official) {}
