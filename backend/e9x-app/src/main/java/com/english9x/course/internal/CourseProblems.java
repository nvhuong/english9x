package com.english9x.course.internal;

import java.net.URI;
import java.util.List;
import org.springframework.http.HttpStatus;
import org.springframework.http.ProblemDetail;
import org.springframework.web.ErrorResponseException;

/** The problem+json answers of the course endpoints. Details name the rule, never an answer, a path of the server or a stack. */
final class CourseProblems {

    /** One entry of {@code errors[]}: same JSON shape as {@code platform.web.ProblemError}; {@code field} is the JSON path inside the request body. */
    record FieldProblem(String field, Integer row, String code, String message) {
        FieldProblem(String field, String message) {
            this(field, firstIndex(field), "invalid", message);
        }

        FieldProblem(String field, String code, String message) {
            this(field, firstIndex(field), code, message);
        }

        private static Integer firstIndex(String field) {
            int open = field == null ? -1 : field.indexOf('[');
            int close = open < 0 ? -1 : field.indexOf(']', open);
            if (open < 0 || close < 0) {
                return null;
            }
            try {
                return Integer.valueOf(field.substring(open + 1, close));
            } catch (NumberFormatException e) {
                return null;
            }
        }
    }

    private CourseProblems() {}

    static ErrorResponseException invalid(List<FieldProblem> errors) {
        return of(HttpStatus.UNPROCESSABLE_ENTITY, "course-invalid", "Course not valid",
                "The course does not match its form. See errors for each field.", errors);
    }

    static ErrorResponseException notFound() {
        return of(HttpStatus.NOT_FOUND, "not-found", "Not found", "There is no such course, or you may not open it.", List.of());
    }

    static ErrorResponseException underReview() {
        return of(HttpStatus.CONFLICT, "version-under-review", "Version under review",
                "The latest version is being reviewed and cannot be edited. Wait for the decision.", List.of());
    }

    static ErrorResponseException anotherAuthorsDraft() {
        return of(HttpStatus.CONFLICT, "draft-of-another-author", "Draft of another author",
                "Only the author of the open draft can edit or submit it. Ask them, or wait until it is decided.", List.of());
    }

    static ErrorResponseException retired() {
        return of(HttpStatus.CONFLICT, "version-archived", "Retired",
                "The latest version of this course has been archived; it cannot be edited.", List.of());
    }

    static ErrorResponseException notEditable() {
        return of(HttpStatus.CONFLICT, "version-not-editable", "Nothing to submit",
                "The latest version is not a draft: there is nothing to submit.", List.of());
    }

    static ErrorResponseException codeTaken() {
        return of(HttpStatus.CONFLICT, "course-code-taken", "Code taken", "Another course already uses this code.", List.of());
    }

    static ErrorResponseException conflict() {
        return of(HttpStatus.CONFLICT, "version-conflict", "Changed meanwhile",
                "Somebody changed this at the same moment. Reload it and try again.", List.of());
    }

    private static ErrorResponseException of(HttpStatus status, String slug, String title, String detail, List<FieldProblem> errors) {
        ProblemDetail p = ProblemDetail.forStatusAndDetail(status, detail);
        p.setType(URI.create("urn:e9x:problem:" + slug));
        p.setTitle(title);
        p.setProperty("errors", errors);
        return new ErrorResponseException(status, p, null);
    }
}
