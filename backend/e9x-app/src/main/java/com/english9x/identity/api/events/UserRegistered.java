package com.english9x.identity.api.events;

import java.time.Instant;
import java.util.UUID;

/**
 * A learner created an account (email + password; a Google sign-up publishes it too). Carries no secret: the
 * verification token goes to the person by mail only. Published inside the registration transaction.
 */
public record UserRegistered(UUID userId, String email, Instant occurredAt) {}
