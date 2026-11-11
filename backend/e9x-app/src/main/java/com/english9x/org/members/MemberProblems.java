package com.english9x.org.members;

import java.net.URI;
import java.util.List;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.ProblemDetail;
import org.springframework.web.ErrorResponseException;

/** problem+json answers of the member endpoints; {@code errors[]} has the shape of the other org problems (field, row, code, message). */
final class MemberProblems {

    record RowProblem(String field, Integer row, String code, String message) {}

    private MemberProblems() {}

    static ErrorResponseException notFound() {
        return of(HttpStatus.NOT_FOUND, "not-found", "Not found", "The resource does not exist.", List.of(), null);
    }

    /** One answer for an unknown, expired, revoked, used-up or malformed code: nothing says which. */
    static ErrorResponseException joinCodeInvalid() {
        return of(HttpStatus.GONE, "join-code-invalid", "Join code not valid", "This code is invalid, has expired, was revoked or has been used up.", List.of(), null);
    }

    static ErrorResponseException tooManyAttempts(long retryAfterSeconds) {
        HttpHeaders h = new HttpHeaders();
        h.set(HttpHeaders.RETRY_AFTER, Long.toString(retryAfterSeconds));
        return of(HttpStatus.TOO_MANY_REQUESTS, "too-many-attempts", "Too many attempts", "Too many attempts. Try again later.", List.of(), h);
    }

    static ErrorResponseException lastOrgAdmin() {
        return of(HttpStatus.CONFLICT, "last-org-admin", "Last organisation admin", "An organisation must keep at least one ORG_ADMIN.", List.of(), null);
    }

    static ErrorResponseException validation(List<RowProblem> errors) {
        return of(HttpStatus.BAD_REQUEST, "validation-failed", "Validation failed", "One or more fields are invalid.", errors, null);
    }

    /** The workbook has bad rows: nothing was invited. 422 like the content import. */
    static ErrorResponseException importRejected(List<RowProblem> errors) {
        return of(HttpStatus.UNPROCESSABLE_ENTITY, "import-has-errors", "The file has errors", "Fix the listed rows and upload the file again; nobody was invited.", errors, null);
    }

    static ErrorResponseException notWorkbook() {
        return of(HttpStatus.BAD_REQUEST, "not-a-workbook", "Not an Excel workbook", "The file must be an .xlsx workbook of at most 2 MB.", List.of(), null);
    }

    private static ErrorResponseException of(HttpStatus status, String slug, String title, String detail, List<RowProblem> errors, HttpHeaders headers) {
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
