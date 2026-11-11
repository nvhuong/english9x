package com.english9x.grading.teacher;

import java.net.URI;
import java.util.List;
import org.springframework.http.HttpStatus;
import org.springframework.http.ProblemDetail;
import org.springframework.web.ErrorResponseException;

/** The problem+json answers of the teachers' queue (same JSON shape as platform.web.Problem, which this module may not import). */
final class TeacherProblems {

    record FieldProblem(String field, Integer row, String code, String message) {}

    private TeacherProblems() {}

    static ErrorResponseException notFound() {
        return of(HttpStatus.NOT_FOUND, "not-found", "Not found", "The resource does not exist.", List.of());
    }

    static ErrorResponseException conflict(String slug, String title, String detail) {
        return of(HttpStatus.CONFLICT, slug, title, detail, List.of());
    }

    static ErrorResponseException validation(String field, String code, String message) {
        return of(HttpStatus.BAD_REQUEST, "validation-failed", "Validation failed", "One or more fields are invalid.", List.of(new FieldProblem(field, null, code, message)));
    }

    static ErrorResponseException unprocessable(String slug, String title, String field, String code, String detail) {
        return of(HttpStatus.UNPROCESSABLE_ENTITY, slug, title, detail, List.of(new FieldProblem(field, null, code, detail)));
    }

    private static ErrorResponseException of(HttpStatus status, String slug, String title, String detail, List<FieldProblem> errors) {
        ProblemDetail p = ProblemDetail.forStatusAndDetail(status, detail);
        p.setType(URI.create("urn:e9x:problem:" + slug));
        p.setTitle(title);
        p.setProperty("errors", errors);
        return new ErrorResponseException(status, p, null);
    }
}
