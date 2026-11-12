package com.english9x.analytics.results;

import java.net.URI;
import org.springframework.http.HttpStatus;
import org.springframework.http.ProblemDetail;
import org.springframework.web.ErrorResponseException;

/** The problem+json answers of the results endpoints. */
final class ResultsProblems {

    private ResultsProblems() {}

    static ErrorResponseException notFound() {
        return of(HttpStatus.NOT_FOUND, "results-not-found", "Not found", "There is no such attempt, or it is not yours.");
    }

    /** The attempt is still going: the answers, the explanations and the scores of an attempt are shown only after it was submitted. */
    static ErrorResponseException notSubmitted() {
        return of(HttpStatus.CONFLICT, "results-attempt-not-submitted", "Not submitted yet", "The result of an attempt is available after you submit it.");
    }

    private static ErrorResponseException of(HttpStatus status, String slug, String title, String detail) {
        ProblemDetail p = ProblemDetail.forStatusAndDetail(status, detail);
        p.setType(URI.create("urn:e9x:problem:" + slug));
        p.setTitle(title);
        return new ErrorResponseException(status, p, null);
    }
}
