package com.english9x.workflow.api;

import java.net.URI;
import org.springframework.http.HttpStatus;
import org.springframework.http.ProblemDetail;
import org.springframework.web.ErrorResponseException;

/** 409 {@code urn:e9x:problem:review-conflict}: the action does not fit the current state (already decided, wrong status, lost a race). */
public class ReviewConflictException extends ErrorResponseException {
    private static final long serialVersionUID = 1L;

    public ReviewConflictException(String message) {
        super(HttpStatus.CONFLICT, problem(message), null);
    }

    private static ProblemDetail problem(String message) {
        ProblemDetail p = ProblemDetail.forStatusAndDetail(HttpStatus.CONFLICT, message);
        p.setType(URI.create("urn:e9x:problem:review-conflict"));
        p.setTitle("Review conflict");
        return p;
    }
}
