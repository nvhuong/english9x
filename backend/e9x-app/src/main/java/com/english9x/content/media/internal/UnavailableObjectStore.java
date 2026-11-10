package com.english9x.content.media.internal;

import java.net.URI;
import java.nio.file.Path;
import java.time.Duration;

/** Media storage is not configured ({@code e9x.media.storage.endpoint} is blank): every call is a clean 503. */
final class UnavailableObjectStore implements ObjectStore {

    private static MediaStorageException off() {
        return new MediaStorageException("media storage is not configured (e9x.media.storage.endpoint is blank)");
    }

    @Override
    public void ensureBucket() {}

    @Override
    public void put(String key, Path file, String contentType) {
        throw off();
    }

    @Override
    public void get(String key, Path target) {
        throw off();
    }

    @Override
    public void delete(String key) {}

    @Override
    public boolean exists(String key) {
        throw off();
    }

    @Override
    public URI presignGet(String key, Duration ttl) {
        throw off();
    }
}
