package com.english9x.content.media.api;

import java.nio.file.Path;

/**
 * Private scratch objects in the media bucket for work that spans a request and a background job (the Excel and the zip of an import, and its error
 * report). Keys live under {@code staging/} and are made of {@code [a-z0-9/._-]} only; they are ids the server made, never a client's name.
 * Not an access path: whoever calls this has decided who may see the file. No signed link is ever made for a staged file.
 */
public interface StagedFiles {

    void put(String key, Path file, String contentType);

    /** Downloads the object into {@code target} (replaced when it exists). */
    void get(String key, Path target);

    /** Deleting what is not there is not an error; a store that fails is logged and ignored (this is cleanup). */
    void deleteQuietly(String key);

    /**
     * Deletes the object, THROWING when the store cannot be reached. For a caller that must not silently orphan the object (erasing biometric voice data under Nghị
     * định 13/2023): a failure fails the caller's transaction so neither side is half-done. Deleting what is not there is still not an error.
     */
    void delete(String key);
}
