package com.english9x.workflow.api.events;

import java.util.Optional;
import java.util.UUID;

/** A content version became PUBLISHED after approval. */
public record ContentPublished(String type, UUID versionId, Optional<UUID> ownerOrgId, String examCode) {}
