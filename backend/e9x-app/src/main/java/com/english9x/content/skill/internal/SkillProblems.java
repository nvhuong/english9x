package com.english9x.content.skill.internal;

import java.net.URI;
import java.util.List;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.http.ProblemDetail;
import org.springframework.web.ErrorResponseException;

/** The problem+json answers of the grading-skill endpoints. Same {@code errors[]} shape as the other CMS endpoints (field = JSON path in the body). */
final class SkillProblems {

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

    private SkillProblems() {}

    static ErrorResponseException invalid(List<FieldProblem> errors) {
        return of(HttpStatus.UNPROCESSABLE_ENTITY, "grading-skill-invalid", "Grading skill not valid",
                "The grading skill does not match its form. See errors for each field.", errors, null);
    }

    static ErrorResponseException exists(UUID skillId) {
        return of(HttpStatus.CONFLICT, "grading-skill-exists", "Grading skill exists",
                "There is already a grading skill for this question type. Edit it (it gets a new version once published).", List.of(), skillId);
    }

    /** The dry run calls the AI grader of P4.2, which does not exist yet; nothing is sent anywhere meanwhile. */
    static ErrorResponseException dryRunUnavailable() {
        return of(HttpStatus.NOT_IMPLEMENTED, "dry-run-unavailable", "Dry run not available yet",
                "Running a skill on a sample answer needs the AI grader, which is not built yet. The skill is saved and reviewed as usual.", List.of(), null);
    }

    static ErrorResponseException notFound() {
        return of(HttpStatus.NOT_FOUND, "not-found", "Not found", "There is no such grading skill, or you may not open it.", List.of(), null);
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

    private static ErrorResponseException of(HttpStatus status, String slug, String title, String detail, List<FieldProblem> errors, UUID skillId) {
        ProblemDetail p = ProblemDetail.forStatusAndDetail(status, detail);
        p.setType(URI.create("urn:e9x:problem:" + slug));
        p.setTitle(title);
        p.setProperty("errors", errors);
        if (skillId != null) {
            p.setProperty("skillId", skillId.toString());
        }
        return new ErrorResponseException(status, p, null);
    }
}
