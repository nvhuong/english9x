package com.english9x.content.scoring.internal;

import java.net.URI;
import java.util.List;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.http.ProblemDetail;
import org.springframework.web.ErrorResponseException;

/** The problem+json answers of the score-table endpoints. Same {@code errors[]} shape as the question and lesson endpoints (field = JSON path in the body). */
final class ScoreProblems {

    record FieldProblem(String field, Integer row, String code, String message) {
        FieldProblem(String field, String message) {
            this(field, firstIndex(field), "invalid", message);
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

    private ScoreProblems() {}

    static ErrorResponseException invalid(List<FieldProblem> errors) {
        return of(HttpStatus.UNPROCESSABLE_ENTITY, "score-table-invalid", "Score table not valid",
                "The score table does not match its form. See errors for each field.", errors, null);
    }

    /** TEST-scope tables wait for P2.6 (tests do not exist yet); the database refuses them too. */
    static ErrorResponseException testScopeUnavailable() {
        return of(HttpStatus.CONFLICT, "test-scope-unavailable", "Per-test tables are not available yet",
                "A conversion table for one test can be made once tests can be built. Use scope EXAM for the default of the exam.", List.of(), null);
    }

    static ErrorResponseException exists(UUID tableId) {
        return of(HttpStatus.CONFLICT, "score-table-exists", "Score table exists",
                "There is already a table for this exam and skill. Edit it (it gets a new version once published).", List.of(), tableId);
    }

    static ErrorResponseException notFound() {
        return of(HttpStatus.NOT_FOUND, "not-found", "Not found", "There is no such score table, or you may not open it.", List.of(), null);
    }

    static ErrorResponseException underReview() {
        return of(HttpStatus.CONFLICT, "version-under-review", "Version under review",
                "The latest version is being reviewed and cannot be edited. Wait for the decision.", List.of(), null);
    }

    static ErrorResponseException anotherAuthorsDraft() {
        return of(HttpStatus.CONFLICT, "draft-of-another-author", "Draft of another author",
                "Only the author of the open draft can edit or submit it. Ask them, or wait until it is decided.", List.of(), null);
    }

    static ErrorResponseException retired() {
        return of(HttpStatus.CONFLICT, "version-archived", "Retired", "The latest version of this item has been archived; it cannot be edited.", List.of(), null);
    }

    static ErrorResponseException notEditable() {
        return of(HttpStatus.CONFLICT, "version-not-editable", "Nothing to submit", "The latest version is not a draft: there is nothing to submit.", List.of(), null);
    }

    static ErrorResponseException conflict() {
        return of(HttpStatus.CONFLICT, "version-conflict", "Changed meanwhile", "Somebody changed this at the same moment. Reload it and try again.", List.of(), null);
    }

    private static ErrorResponseException of(HttpStatus status, String slug, String title, String detail, List<FieldProblem> errors, UUID tableId) {
        ProblemDetail p = ProblemDetail.forStatusAndDetail(status, detail);
        p.setType(URI.create("urn:e9x:problem:" + slug));
        p.setTitle(title);
        p.setProperty("errors", errors);
        if (tableId != null) {
            p.setProperty("tableId", tableId.toString());
        }
        return new ErrorResponseException(status, p, null);
    }
}
