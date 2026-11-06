package com.english9x.platform.web;

import io.swagger.v3.oas.annotations.media.Schema;
import java.util.List;

/** Documentation shape of every error body (RFC 7807, {@code application/problem+json}); built by {@link ProblemDetailsAdvice}. */
@Schema(name = "Problem", description = "RFC 7807 problem details; errors[] lists invalid fields/rows, empty otherwise")
public record Problem(
        @Schema(description = "URN identifying the problem class, e.g. urn:e9x:problem:validation-failed") String type,
        @Schema(description = "Short summary, stable per type") String title,
        @Schema(description = "HTTP status code") int status,
        @Schema(description = "Explanation for this occurrence; never contains internals") String detail,
        @Schema(description = "Request path", nullable = true) String instance,
        List<ProblemError> errors) {}
