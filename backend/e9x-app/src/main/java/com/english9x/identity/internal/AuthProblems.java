package com.english9x.identity.internal;

import java.net.URI;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.ProblemDetail;
import org.springframework.web.ErrorResponseException;

/** The problem+json answers of the auth endpoints. Details are generic on purpose (no hint about which part was wrong). */
final class AuthProblems {

    private AuthProblems() {}

    static ErrorResponseException invalidCredentials() {
        return of(HttpStatus.UNAUTHORIZED, "invalid-credentials", "Invalid credentials", "Email or password is incorrect.", null);
    }

    static ErrorResponseException invalidRefreshToken() {
        return of(HttpStatus.UNAUTHORIZED, "invalid-refresh-token", "Invalid refresh token", "Sign in again.", null);
    }

    static ErrorResponseException invalidToken() {
        return of(HttpStatus.BAD_REQUEST, "invalid-or-expired-token", "Invalid or expired link",
                "This link is invalid, already used, or has expired.", null);
    }

    static ErrorResponseException invalidLoginCode() {
        return of(HttpStatus.BAD_REQUEST, "invalid-login-code", "Invalid sign-in code", "Start the sign-in again.", null);
    }

    static ErrorResponseException consentRequired() {
        return of(HttpStatus.FORBIDDEN, "consent-required", "Consent required",
                "Consent to personal data processing is required before signing in.", null);
    }

    static ErrorResponseException tooManyAttempts(long retryAfterSeconds) {
        HttpHeaders h = new HttpHeaders();
        h.set(HttpHeaders.RETRY_AFTER, Long.toString(retryAfterSeconds));
        return of(HttpStatus.TOO_MANY_REQUESTS, "too-many-attempts", "Too many attempts",
                "Too many attempts. Try again later.", h);
    }

    static ErrorResponseException googleDisabled() {
        return of(HttpStatus.NOT_FOUND, "google-login-disabled", "Not found", "Google sign-in is not available.", null);
    }

    private static ErrorResponseException of(HttpStatus status, String slug, String title, String detail, HttpHeaders headers) {
        ProblemDetail p = ProblemDetail.forStatusAndDetail(status, detail);
        p.setType(URI.create("urn:e9x:problem:" + slug));
        p.setTitle(title);
        return new ErrorResponseException(status, p, null) {
            @Override
            public HttpHeaders getHeaders() {
                return headers == null ? HttpHeaders.EMPTY : headers;
            }
        };
    }
}
