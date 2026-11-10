package com.english9x.billing.plan;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import java.util.UUID;

final class PlanDtos {

    private PlanDtos() {}

    @Schema(name = "PlanView")
    record PlanView(
            @Schema(requiredMode = Schema.RequiredMode.REQUIRED) UUID id,
            @Schema(requiredMode = Schema.RequiredMode.REQUIRED) String name,
            @Schema(requiredMode = Schema.RequiredMode.REQUIRED, description = "Price in VND, locked onto an order when it is placed") long priceVnd,
            @Schema(requiredMode = Schema.RequiredMode.REQUIRED) int months,
            @Schema(requiredMode = Schema.RequiredMode.REQUIRED, description = "Most courses one order of this plan may select") int maxCourses,
            @Schema(requiredMode = Schema.RequiredMode.REQUIRED, description = "On sale; a learner sees only active plans") boolean active) {}

    @Schema(name = "PlanRequest")
    record PlanRequest(
            @Schema(requiredMode = Schema.RequiredMode.REQUIRED) @NotBlank @Size(max = 100) String name,
            @Schema(requiredMode = Schema.RequiredMode.REQUIRED) @NotNull @Min(1) @Max(1_000_000_000_000L) Long priceVnd,
            @Schema(requiredMode = Schema.RequiredMode.REQUIRED) @NotNull @Min(1) @Max(60) Integer months,
            @Schema(requiredMode = Schema.RequiredMode.REQUIRED) @NotNull @Min(1) @Max(50) Integer maxCourses,
            @Schema(requiredMode = Schema.RequiredMode.REQUIRED) @NotNull Boolean active) {}
}
