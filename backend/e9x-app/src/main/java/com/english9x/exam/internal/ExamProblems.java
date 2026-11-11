package com.english9x.exam.internal;

import java.net.URI;
import org.springframework.http.HttpStatus;
import org.springframework.http.ProblemDetail;
import org.springframework.web.ErrorResponseException;

/** The problem+json answers of the exam endpoints. */
final class ExamProblems {

    private ExamProblems() {}

    static ErrorResponseException notFound() {
        return of(HttpStatus.NOT_FOUND, "exam-not-found", "Not found", "There is no such attempt, test or question, or it is not yours.");
    }

    static ErrorResponseException notEntitled() {
        return of(HttpStatus.FORBIDDEN, "exam-not-entitled", "No access", "You do not hold a plan that includes this exam.");
    }

    static ErrorResponseException rejected(String slug, String detail) {
        return of(HttpStatus.CONFLICT, slug, "Not accepted", detail);
    }

    static ErrorResponseException gone() {
        return of(HttpStatus.GONE, "exam-audio-played", "Already played", "This recording can be played once and it has been played.");
    }

    static ErrorResponseException tooLarge(String what) {
        return of(HttpStatus.PAYLOAD_TOO_LARGE, "exam-too-large", "Too large", what + " is larger than the limit.");
    }

    static ErrorResponseException badRequest(String slug, String detail) {
        return of(HttpStatus.BAD_REQUEST, slug, "Invalid request", detail);
    }

    static ErrorResponseException unsupportedRecording() {
        return of(HttpStatus.UNSUPPORTED_MEDIA_TYPE, "exam-recording-type", "Recording type not allowed", "Send one WebM (Opus) audio file in the part named \"file\".");
    }

    private static ErrorResponseException of(HttpStatus status, String slug, String title, String detail) {
        ProblemDetail p = ProblemDetail.forStatusAndDetail(status, detail);
        p.setType(URI.create("urn:e9x:problem:" + slug));
        p.setTitle(title);
        return new ErrorResponseException(status, p, null);
    }
}
