package com.english9x.analytics.reports;

import java.net.URI;
import org.springframework.http.HttpStatus;
import org.springframework.http.ProblemDetail;
import org.springframework.web.ErrorResponseException;

/** The problem+json answers of the report endpoints. */
final class ReportsProblems {

    private ReportsProblems() {}

    static ErrorResponseException notFound() {
        ProblemDetail p = ProblemDetail.forStatusAndDetail(HttpStatus.NOT_FOUND, "The resource does not exist.");
        p.setType(URI.create("urn:e9x:problem:not-found"));
        p.setTitle("Not found");
        return new ErrorResponseException(HttpStatus.NOT_FOUND, p, null);
    }
}
