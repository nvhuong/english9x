package com.english9x.workflow.api;

import java.net.URI;
import org.springframework.http.HttpStatus;
import org.springframework.http.ProblemDetail;
import org.springframework.web.ErrorResponseException;

/** 404 {@code urn:e9x:problem:review-not-found}: the version or review does not exist, or the caller may not see it (never told apart). */
public class ReviewNotFoundException extends ErrorResponseException {
    private static final long serialVersionUID = 1L;

    public ReviewNotFoundException() {
        super(HttpStatus.NOT_FOUND, problem(), null);
    }

    private static ProblemDetail problem() {
        ProblemDetail p = ProblemDetail.forStatusAndDetail(HttpStatus.NOT_FOUND, "The review or version was not found.");
        p.setType(URI.create("urn:e9x:problem:review-not-found"));
        p.setTitle("Not found");
        return p;
    }
}
