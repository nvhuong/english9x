package com.english9x.content.media.api;

import java.nio.file.Path;
import java.util.UUID;

/**
 * The bytes of a stored file, for server-side work that must read it (the dry run of a Speaking grading skill sends a sample recording to the AI grader).
 * Asks {@link MediaAssets#find} first, in the caller's transaction: a file the caller may not see is reported as missing, exactly like one that does not exist.
 */
public interface MediaFiles {

    /**
     * Downloads the file into {@code target} (an existing file is replaced).
     *
     * @throws java.util.NoSuchElementException when there is no such file for the caller
     * @throws StagedFilesException when the store cannot be read
     */
    MediaAssets.MediaInfo copyTo(UUID mediaId, Path target);
}
