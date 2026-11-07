package com.english9x.workflow.api;

import java.net.URI;
import java.util.List;
import java.util.Map;
import org.springframework.http.HttpStatus;
import org.springframework.http.ProblemDetail;
import org.springframework.web.ErrorResponseException;

/** 400 {@code urn:e9x:problem:validation-failed}: the request itself is wrong (blank comment, unknown content type, bundle too large). */
public class InvalidReviewInputException extends ErrorResponseException {
    private static final long serialVersionUID = 1L;

    public InvalidReviewInputException(String field, String code, String message) {
        super(HttpStatus.BAD_REQUEST, problem(field, code, message), null);
    }

    private static ProblemDetail problem(String field, String code, String message) {
        ProblemDetail p = ProblemDetail.forStatusAndDetail(HttpStatus.BAD_REQUEST, "One or more fields are invalid.");
        p.setType(URI.create("urn:e9x:problem:validation-failed"));
        p.setTitle("Validation failed");
        Map<String, Object> error = new java.util.LinkedHashMap<>();
        error.put("field", field);
        error.put("row", null);
        error.put("code", code);
        error.put("message", message);
        p.setProperty("errors", List.of(error));
        return p;
    }
}
