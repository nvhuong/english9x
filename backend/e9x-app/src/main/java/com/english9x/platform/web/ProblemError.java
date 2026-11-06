package com.english9x.platform.web;

import io.swagger.v3.oas.annotations.media.Schema;

/**
 * One entry of {@code errors[]} in a problem response. {@code field} is the path of the offending value in the
 * request ({@code rows[1].sku}); {@code row} is the zero-based index of the offending element when the path goes
 * through a list (import rows, line items), otherwise null.
 */
@Schema(name = "ProblemError")
public record ProblemError(
        @Schema(description = "Path of the offending value in the request, e.g. rows[1].sku", nullable = true) String field,
        @Schema(description = "Zero-based index of the offending list element, null when not row-specific", nullable = true)
                Integer row,
        @Schema(description = "Machine-readable constraint code, e.g. NotBlank", nullable = true) String code,
        @Schema(description = "Human-readable message") String message) {}
