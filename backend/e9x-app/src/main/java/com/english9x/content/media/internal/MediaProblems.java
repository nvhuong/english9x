package com.english9x.content.media.internal;

import java.net.URI;
import org.springframework.http.HttpStatus;
import org.springframework.http.ProblemDetail;
import org.springframework.util.unit.DataSize;
import org.springframework.web.ErrorResponseException;

/** The problem+json answers of the media endpoints. Details name the rule, never a path, a stack or the bytes. */
final class MediaProblems {

    private MediaProblems() {}

    static ErrorResponseException notAllowed() {
        return of(HttpStatus.UNSUPPORTED_MEDIA_TYPE, "media-type-not-allowed", "File type not allowed",
                "The file is not one of the allowed types: MP3, M4A, WebM or OGG audio, PNG, JPG or WebP images, MP4 or WebM video. "
                        + "The type is decided by what the file contains, not by its name.");
    }

    static ErrorResponseException extensionMismatch() {
        return of(HttpStatus.UNSUPPORTED_MEDIA_TYPE, "media-extension-mismatch", "File name does not match its content",
                "The extension of the file name does not match what the file contains. Rename the file or export it again.");
    }

    static ErrorResponseException tooLarge(DataSize limit) {
        return of(HttpStatus.PAYLOAD_TOO_LARGE, "media-too-large", "File too large", "The file is larger than the limit of " + show(limit) + ".");
    }

    static ErrorResponseException unreadable() {
        return of(HttpStatus.UNPROCESSABLE_ENTITY, "media-unreadable", "File cannot be read",
                "The file looks like an allowed type but its audio or video could not be read, so its length is unknown. It may be damaged or cut short.");
    }

    static ErrorResponseException fileMissing() {
        return of(HttpStatus.BAD_REQUEST, "media-file-missing", "No file", "Send one non-empty file in the multipart part named \"file\".");
    }

    static ErrorResponseException examMissing() {
        return of(HttpStatus.BAD_REQUEST, "media-exam-missing", "No exam", "Say which exam the file is for with the examCode parameter.");
    }

    static ErrorResponseException notFound() {
        return of(HttpStatus.NOT_FOUND, "media-not-found", "Not found", "There is no such file, or you may not open it.");
    }

    static ErrorResponseException storageUnavailable() {
        return of(HttpStatus.SERVICE_UNAVAILABLE, "media-storage-unavailable", "Media storage unavailable",
                "The file could not be handled right now. Try again in a moment.");
    }

    static String show(DataSize size) {
        long bytes = size.toBytes();
        if (bytes >= 1024 * 1024 && bytes % (1024 * 1024) == 0) {
            return bytes / (1024 * 1024) + " MB";
        }
        if (bytes >= 1024) {
            return Math.round(bytes / 1024.0) + " KB";
        }
        return bytes + " bytes";
    }

    private static ErrorResponseException of(HttpStatus status, String slug, String title, String detail) {
        ProblemDetail p = ProblemDetail.forStatusAndDetail(status, detail);
        p.setType(URI.create("urn:e9x:problem:" + slug));
        p.setTitle(title);
        return new ErrorResponseException(status, p, null);
    }
}
