package com.english9x.content.media.internal;

/** The object store cannot be reached or refused the call (or media storage is not configured). Answered as 503; the cause is logged, never sent. */
class MediaStorageException extends RuntimeException {
    MediaStorageException(String message, Throwable cause) {
        super(message, cause);
    }

    MediaStorageException(String message) {
        super(message);
    }
}
