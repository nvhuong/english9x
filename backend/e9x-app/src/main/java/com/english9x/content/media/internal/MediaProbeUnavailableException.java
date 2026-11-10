package com.english9x.content.media.internal;

/** ffprobe could not be started: the server is misconfigured, the file may be fine. Answered as 503. */
class MediaProbeUnavailableException extends RuntimeException {
    MediaProbeUnavailableException(String message, Throwable cause) {
        super(message, cause);
    }
}
