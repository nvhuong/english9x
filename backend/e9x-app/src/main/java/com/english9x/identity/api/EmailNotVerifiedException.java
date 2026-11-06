package com.english9x.identity.api;

import java.net.URI;
import org.springframework.http.HttpStatus;
import org.springframework.http.ProblemDetail;
import org.springframework.web.ErrorResponseException;

/** 403 {@code urn:e9x:problem:email-not-verified}: the action needs a verified email address. */
public class EmailNotVerifiedException extends ErrorResponseException {

    public EmailNotVerifiedException() {
        super(HttpStatus.FORBIDDEN, problem(), null);
    }

    private static ProblemDetail problem() {
        ProblemDetail p = ProblemDetail.forStatusAndDetail(
                HttpStatus.FORBIDDEN, "Verify your email address before doing this.");
        p.setType(URI.create("urn:e9x:problem:email-not-verified"));
        p.setTitle("Email not verified");
        return p;
    }
}
