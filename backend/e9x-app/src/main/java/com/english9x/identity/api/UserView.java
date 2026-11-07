package com.english9x.identity.api;

import java.util.UUID;

/** What other modules may know about a user. Never the password hash. */
public record UserView(UUID id, String email, String displayName, boolean emailVerified, String locale) {}
