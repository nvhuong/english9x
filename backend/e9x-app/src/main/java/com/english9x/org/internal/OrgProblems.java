package com.english9x.org.internal;

import java.net.URI;
import java.util.List;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.ProblemDetail;
import org.springframework.web.ErrorResponseException;

/** The problem+json answers of the organisation endpoints. */
final class OrgProblems {

    /** One entry of {@code errors[]}: same JSON shape as {@code platform.web.ProblemError} (which the org module may not import). */
    record FieldProblem(String field, Integer row, String code, String message) {}

    private OrgProblems() {}

    static ErrorResponseException notFound() {
        return of(HttpStatus.NOT_FOUND, "not-found", "Not found", "The resource does not exist.", List.of(), null);
    }

    /** One answer for a forged, unknown, used, expired or revoked token: nothing says which. */
    static ErrorResponseException invitationInvalid() {
        return of(HttpStatus.NOT_FOUND, "invitation-invalid", "Invitation not valid",
                "This invitation is invalid, already used, revoked or has expired.", List.of(), null);
    }

    static ErrorResponseException invitationEmailMismatch() {
        return of(HttpStatus.FORBIDDEN, "invitation-email-mismatch", "Invitation is for another address",
                "Sign in with the email address this invitation was sent to.", List.of(), null);
    }

    static ErrorResponseException validation(List<FieldProblem> errors) {
        return of(HttpStatus.BAD_REQUEST, "validation-failed", "Validation failed", "One or more fields are invalid.", errors, null);
    }

    /** F50: an ENTERPRISE has no teachers, so it cannot pick its own graders or a teacher mode (spec §4). 400: the organisation's type forbids the option. */
    static ErrorResponseException gradingNotAllowedForEnterprise() {
        return of(HttpStatus.BAD_REQUEST, "grading-not-allowed", "Grading option not allowed",
                "An enterprise's learners are always graded by English9x: a centre grader and the teacher modes are not available.", List.of(), null);
    }

    static ErrorResponseException roleNotAllowed(List<FieldProblem> errors) {
        return of(HttpStatus.CONFLICT, "role-not-allowed", "Role not allowed",
                "An ENTERPRISE organisation cannot have AUTHOR, REVIEWER or TEACHER.", errors, null);
    }

    static ErrorResponseException lastOrgAdmin() {
        return of(HttpStatus.CONFLICT, "last-org-admin", "Last organisation admin",
                "An organisation must keep at least one ORG_ADMIN.", List.of(), null);
    }

    static ErrorResponseException tooManyInvitations(long retryAfterSeconds) {
        HttpHeaders h = new HttpHeaders();
        h.set(HttpHeaders.RETRY_AFTER, Long.toString(retryAfterSeconds));
        return of(HttpStatus.TOO_MANY_REQUESTS, "too-many-invitations", "Too many invitations",
                "Too many invitations sent recently. Try again later.", List.of(), h);
    }

    private static ErrorResponseException of(HttpStatus status, String slug, String title, String detail,
                                             List<FieldProblem> errors, HttpHeaders headers) {
        ProblemDetail p = ProblemDetail.forStatusAndDetail(status, detail);
        p.setType(URI.create("urn:e9x:problem:" + slug));
        p.setTitle(title);
        p.setProperty("errors", errors);
        return new ErrorResponseException(status, p, null) {
            @Override
            public HttpHeaders getHeaders() {
                return headers == null ? HttpHeaders.EMPTY : headers;
            }
        };
    }
}
