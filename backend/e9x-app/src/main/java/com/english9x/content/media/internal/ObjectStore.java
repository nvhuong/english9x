package com.english9x.content.media.internal;

import java.nio.file.Path;
import java.time.Duration;

/** The private bucket. Keys are row ids; nothing a client wrote ever becomes a key. */
interface ObjectStore {

    /** Creates the bucket when it is missing; harmless when it exists. */
    void ensureBucket();

    void put(String key, Path file, String contentType);

    /** Downloads the object into {@code target} (an existing file is replaced). @throws MediaStorageException when it is not there or cannot be read */
    void get(String key, Path target);

    /** Deleting what is not there is not an error. */
    void delete(String key);

    boolean exists(String key);

    /** A GET link for {@code ttl}, made for the public address. Offline: nothing is sent to the store. */
    java.net.URI presignGet(String key, Duration ttl);
}
