package com.english9x.exam.api.events;

import java.time.Instant;
import java.util.Optional;
import java.util.UUID;

/** An exam attempt was submitted (by the learner or by the auto-submit job at the deadline). Published once per attempt, after commit. */
public record AttemptSubmitted(UUID attemptId, UUID userId, UUID testVersionId, Optional<UUID> orgId, Instant submittedAt, String reason, boolean interrupted) {}
