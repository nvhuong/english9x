package com.english9x.content.media.internal;

/** The bytes look like an allowed type but ffprobe cannot read them (or reads no usable duration): the file is refused, never stored with a guess. */
class MediaUnreadableException extends RuntimeException {
    MediaUnreadableException(String message) {
        super(message);
    }
}
