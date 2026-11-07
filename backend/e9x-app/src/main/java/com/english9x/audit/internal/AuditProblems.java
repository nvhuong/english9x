package com.english9x.audit.internal;

import java.net.URI;
import java.util.List;
import org.springframework.http.HttpStatus;
import org.springframework.http.ProblemDetail;
import org.springframework.web.ErrorResponseException;

/** The problem+json answers of the audit endpoints (same JSON shape as platform.web.Problem, which this module may not import). */
final class AuditProblems {

    record FieldProblem(String field, Integer row, String code, String message) {}

    private AuditProblems() {}

    static ErrorResponseException validation(String field, String code, String message) {
        ProblemDetail p = ProblemDetail.forStatusAndDetail(HttpStatus.BAD_REQUEST, "One or more fields are invalid.");
        p.setType(URI.create("urn:e9x:problem:validation-failed"));
        p.setTitle("Validation failed");
        p.setProperty("errors", List.of(new FieldProblem(field, null, code, message)));
        return new ErrorResponseException(HttpStatus.BAD_REQUEST, p, null);
    }
}
