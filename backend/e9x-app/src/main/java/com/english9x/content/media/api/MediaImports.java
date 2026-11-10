package com.english9x.content.media.api;

import java.nio.file.Path;
import java.util.UUID;

/**
 * What the Excel import (P2.7) needs of the media part: look at a file of the uploaded zip the way an upload would, and store it as a media file.
 * Both run in the caller's bound context and transaction like {@code POST /api/cms/media}: the rights are the same (the AUTHOR of the exam, in the
 * owner's context, as themselves; the row-level policy asks the database again).
 */
public interface MediaImports {

    /** What a file is, found from its bytes. */
    record Inspection(MediaKind kind, String mimeType, Long durationMs) {}

    /**
     * Sniffs and probes a file without storing it: what {@link #store} would accept.
     *
     * @return the kind, or {@code null} when the file would be refused (not an allowed container, the name disagrees with the bytes, unreadable);
     *         {@code refusal} then holds a short reason that names no path
     */
    Inspection inspect(Path file, String filename, StringBuilder refusal);

    /**
     * Stores the file as a media file of {@code examCode} (object first, then the row) and returns its id. The temp file stays the caller's.
     * The object is removed again when this call fails; when the CALLER's transaction later rolls back, the caller calls {@link #discard}.
     */
    UUID store(String examCode, Path file, String filename);

    /** Removes the stored object of a file whose row was rolled back. Never throws. */
    void discard(UUID mediaId);
}
