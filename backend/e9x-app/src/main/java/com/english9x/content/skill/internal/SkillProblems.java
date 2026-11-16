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

    /** The application runs without an AI grader (no {@code SkillDryRunner}); nothing is sent anywhere. */
    static ErrorResponseException dryRunUnavailable() {
        return of(HttpStatus.NOT_IMPLEMENTED, "dry-run-unavailable", "Dry run not available",
                "Running a skill on a sample answer needs the AI grader, which this server does not have. The skill is saved and reviewed as usual.", List.of(), null);
    }

    /**
     * The AI grader gave no usable answer. TRANSIENT (timeout, 429, 5xx): 503 ai-grader-unavailable, try later. SCHEMA: 502 dry-run-schema, the model's answer broke the
     * result schema after every retry (the detail lists the reasons: check the schema and the prompt). PERMANENT: 502 ai-grader-refused (no key, a refused request).
     */
    static ErrorResponseException dryRunFailed(com.english9x.content.skill.api.SkillDryRunner.Failure.Kind kind, String reason) {
        return switch (kind) {
            case TRANSIENT -> of(HttpStatus.SERVICE_UNAVAILABLE, "ai-grader-unavailable", "AI grader unavailable",
                    "The AI grader did not answer in time (" + reason + "). Try again in a moment.", List.of(), null);
            case SCHEMA -> of(HttpStatus.BAD_GATEWAY, "dry-run-schema", "Answer does not match the result schema",
                    "The model's answer did not match the result schema of the skill: " + reason, List.of(), null);
            case PERMANENT -> of(HttpStatus.BAD_GATEWAY, "ai-grader-refused", "AI grader refused",
                    "The AI grader could not run this skill: " + reason, List.of(), null);
        };
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
