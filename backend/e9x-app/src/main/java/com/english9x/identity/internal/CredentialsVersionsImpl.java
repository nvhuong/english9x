package com.english9x.identity.internal;

import com.english9x.identity.api.CredentialsVersions;
import java.util.UUID;
import org.springframework.stereotype.Service;

@Service
class CredentialsVersionsImpl implements CredentialsVersions {

    private final AuthRepository repo;

    CredentialsVersionsImpl(AuthRepository repo) {
        this.repo = repo;
    }

    @Override
    public boolean isCurrent(UUID userId, long credentialsVersion) {
        Long current = repo.credentialsVersion(userId);
        return current != null && current == credentialsVersion;
    }
}
