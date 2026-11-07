package com.english9x.notification.internal;

import java.net.URI;
import java.util.List;
import org.springframework.http.HttpStatus;
import org.springframework.http.ProblemDetail;
import org.springframework.web.ErrorResponseException;

/** The problem+json answers of the notification endpoints (same JSON shape as platform.web.Problem, which this module may not import). */
final class NotificationProblems {

    record FieldProblem(String field, Integer row, String code, String message) {}

    private NotificationProblems() {}

    static ErrorResponseException notFound() {
        return of(HttpStatus.NOT_FOUND, "not-found", "Not found", "The resource does not exist.", List.of());
    }

    static ErrorResponseException validation(String field, String code, String message) {
        return of(HttpStatus.BAD_REQUEST, "validation-failed", "Validation failed", "One or more fields are invalid.",
                List.of(new FieldProblem(field, null, code, message)));
    }

    static ErrorResponseException telegramNotConfigured() {
        return of(HttpStatus.SERVICE_UNAVAILABLE, "telegram-not-configured", "Telegram is not available",
                "Telegram notifications are not set up on this server.", List.of());
    }

    /** The webhook's only refusal: no hint at what was wrong. */
    static ErrorResponseException webhookUnauthorized() {
        return of(HttpStatus.UNAUTHORIZED, "unauthorized", "Unauthorized", "Authentication is required.", List.of());
    }

    private static ErrorResponseException of(HttpStatus status, String slug, String title, String detail, List<FieldProblem> errors) {
        ProblemDetail p = ProblemDetail.forStatusAndDetail(status, detail);
        p.setType(URI.create("urn:e9x:problem:" + slug));
        p.setTitle(title);
        p.setProperty("errors", errors);
        return new ErrorResponseException(status, p, null);
    }
}
