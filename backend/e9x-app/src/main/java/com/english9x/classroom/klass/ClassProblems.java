package com.english9x.classroom.klass;

import java.net.URI;
import java.util.List;
import org.springframework.http.HttpStatus;
import org.springframework.http.ProblemDetail;
import org.springframework.web.ErrorResponseException;

/** problem+json answers of the classroom endpoints ({@code errors[]}: field, code, message). */
public final class ClassProblems {

    public record FieldProblem(String field, String code, String message) {}

    private ClassProblems() {}

    public static ErrorResponseException notFound() {
        return of(HttpStatus.NOT_FOUND, "not-found", "Not found", "The resource does not exist.", List.of());
    }

    public static ErrorResponseException unprocessable(String slug, String title, String field, String code, String detail) {
        return of(HttpStatus.UNPROCESSABLE_ENTITY, slug, title, detail, List.of(new FieldProblem(field, code, detail)));
    }

    public static ErrorResponseException unprocessable(String slug, String title, List<FieldProblem> errors, String detail) {
        return of(HttpStatus.UNPROCESSABLE_ENTITY, slug, title, detail, errors);
    }

    private static ErrorResponseException of(HttpStatus status, String slug, String title, String detail, List<FieldProblem> errors) {
        ProblemDetail p = ProblemDetail.forStatusAndDetail(status, detail);
        p.setType(URI.create("urn:e9x:problem:" + slug));
        p.setTitle(title);
        p.setProperty("errors", errors);
        return new ErrorResponseException(status, p, null);
    }
}
