package com.english9x.analytics.progress;

import java.net.URI;
import org.springframework.http.HttpStatus;
import org.springframework.http.ProblemDetail;
import org.springframework.web.ErrorResponseException;

/** The problem+json answers of the progress endpoints. */
final class ProgressProblems {

    private ProgressProblems() {}

    static ErrorResponseException notFound() {
        ProblemDetail p = ProblemDetail.forStatusAndDetail(HttpStatus.NOT_FOUND, "The resource does not exist.");
        p.setType(URI.create("urn:e9x:problem:not-found"));
        p.setTitle("Not found");
        return new ErrorResponseException(HttpStatus.NOT_FOUND, p, null);
    }
}
