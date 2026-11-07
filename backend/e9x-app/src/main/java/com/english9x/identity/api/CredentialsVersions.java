package com.english9x.identity.api;

import java.util.UUID;

/** Is an access token still good? It names the credentials version it was issued under, and this compares that with the account's. */
public interface CredentialsVersions {

    /** False when the version moved on, or when the account no longer exists. */
    boolean isCurrent(UUID userId, long credentialsVersion);
}
