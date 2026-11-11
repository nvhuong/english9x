package com.english9x.learning.internal;

import java.net.URI;
import org.springframework.http.HttpStatus;
import org.springframework.http.ProblemDetail;
import org.springframework.web.ErrorResponseException;

/** The problem+json answers of the learning endpoints. */
final class LearningProblems {

    private LearningProblems() {}

    static ErrorResponseException notFound() {
        return of(HttpStatus.NOT_FOUND, "learning-not-found", "Not found", "There is no such lesson, test or question, or it is not open to you.");
    }

    static ErrorResponseException notEntitled() {
        return of(HttpStatus.FORBIDDEN, "learning-not-entitled", "No access", "You do not hold a plan that includes this course.");
    }

    static ErrorResponseException locked() {
        return of(HttpStatus.CONFLICT, "learning-locked", "Locked", "Finish the earlier lessons of the course first.");
    }

    static ErrorResponseException rejected(String slug, String detail) {
        return of(HttpStatus.CONFLICT, slug, "Not accepted", detail);
    }

    static ErrorResponseException badRequest(String slug, String detail) {
        return of(HttpStatus.BAD_REQUEST, slug, "Invalid request", detail);
    }

    private static ErrorResponseException of(HttpStatus status, String slug, String title, String detail) {
        ProblemDetail p = ProblemDetail.forStatusAndDetail(status, detail);
        p.setType(URI.create("urn:e9x:problem:" + slug));
        p.setTitle(title);
        return new ErrorResponseException(status, p, null);
    }
}
