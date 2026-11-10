package com.english9x.content.media.api;

import java.util.Optional;
import java.util.UUID;

/**
 * What a stored file is. Reads in the caller's transaction, so row-level security answers: the author or reviewer of the file's exam, in the
 * file's owner's context, sees it; anybody else gets {@code Optional.empty()}, the same as for a file that does not exist.
 *
 * <p>Content that points at a file (questions P2.3, groups P2.4, lessons, imports P2.7) stores the {@code mediaId} and, when it is saved,
 * asks {@link #find} and checks {@link MediaInfo#examCode()} against its own exam: a draft can then never point at somebody else's file.
 */
public interface MediaAssets {

    Optional<MediaInfo> find(UUID mediaId);

    /** @param ownerOrgId the owner organisation, empty for English9x */
    record MediaInfo(UUID id, Optional<UUID> ownerOrgId, String examCode, MediaKind kind, String mimeType, long sizeBytes,
                     Optional<Long> durationMs, String originalFilename) {}
}
