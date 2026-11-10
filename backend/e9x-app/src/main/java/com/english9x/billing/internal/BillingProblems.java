package com.english9x.billing.internal;

import java.net.URI;
import java.util.List;
import org.springframework.http.HttpStatus;
import org.springframework.http.ProblemDetail;
import org.springframework.web.ErrorResponseException;

/** The problem+json answers of the billing endpoints (same JSON shape as platform.web.Problem, which this module may not import). */
public final class BillingProblems {

    /** One entry of {@code errors[]}. */
    public record FieldProblem(String field, Integer row, String code, String message) {}

    private BillingProblems() {}

    public static ErrorResponseException validation(List<FieldProblem> errors) {
        return of(HttpStatus.BAD_REQUEST, "validation-failed", "Validation failed", "One or more fields are invalid.", errors);
    }

    public static ErrorResponseException validation(String field, String code, String message) {
        return validation(List.of(new FieldProblem(field, null, code, message)));
    }

    public static ErrorResponseException notFound() {
        return of(HttpStatus.NOT_FOUND, "not-found", "Not found", "The resource does not exist.", List.of());
    }

    /** The plan exists but is not on sale, or does not exist: one answer. */
    public static ErrorResponseException planNotAvailable() {
        return of(HttpStatus.CONFLICT, "plan-not-available", "Plan not available", "This plan is not on sale.", List.of());
    }

    /** The order is not PENDING_PAYMENT any more (already confirmed, rejected or expired): a decision on it changes nothing. */
    public static ErrorResponseException orderNotPending() {
        return of(HttpStatus.CONFLICT, "order-not-pending", "Order not pending", "This order is no longer waiting for payment.", List.of());
    }

    /** Two administrators changed the payment account at the same moment; the later one asks again. */
    public static ErrorResponseException paymentAccountChanged() {
        return of(HttpStatus.CONFLICT, "payment-account-changed", "Payment account changed", "The payment account was changed at the same time. Try again.", List.of());
    }

    /** A reject without a usable reason (422: the request is well-formed, the decision is not complete). */
    public static ErrorResponseException reasonRequired(String message) {
        return of(HttpStatus.UNPROCESSABLE_ENTITY, "reason-required", "Reason required", message, List.of(new FieldProblem("reason", null, "ReasonRequired", message)));
    }

    private static ErrorResponseException of(HttpStatus status, String slug, String title, String detail, List<FieldProblem> errors) {
        ProblemDetail p = ProblemDetail.forStatusAndDetail(status, detail);
        p.setType(URI.create("urn:e9x:problem:" + slug));
        p.setTitle(title);
        p.setProperty("errors", errors);
        return new ErrorResponseException(status, p, null);
    }
}
