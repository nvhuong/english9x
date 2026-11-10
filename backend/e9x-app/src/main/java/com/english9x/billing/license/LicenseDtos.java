package com.english9x.billing.license;

import com.english9x.billing.payment.PaymentInstructions.PayTo;
import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;
import jakarta.validation.constraints.Size;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

final class LicenseDtos {

    private LicenseDtos() {}

    @Schema(name = "LicenseActivation", description = "QR: an order the organisation's administrator pays by bank transfer; CONTRACT: active at once, paid under a contract")
    enum Activation {
        QR,
        CONTRACT
    }

    @Schema(name = "CreateOrgLicenseRequest")
    record CreateLicenseRequest(
            @Schema(requiredMode = Schema.RequiredMode.REQUIRED) @NotNull UUID orgId,
            @Schema(requiredMode = Schema.RequiredMode.REQUIRED, description = "People who can hold a seat at once, 1 to 100000") @NotNull @Min(1) @Max(100_000) Integer seats,
            @Schema(requiredMode = Schema.RequiredMode.REQUIRED, description = "Length in months, 1 to 60") @NotNull @Min(1) @Max(60) Integer months,
            @Schema(requiredMode = Schema.RequiredMode.REQUIRED, description = "Published English9x courses the seats may cover") @NotEmpty @Size(max = 100) List<@NotNull UUID> courseIds,
            @Schema(requiredMode = Schema.RequiredMode.REQUIRED, description = "The agreed price; locked at creation") @NotNull @Positive Long priceVnd,
            @Schema(requiredMode = Schema.RequiredMode.REQUIRED) @NotNull Activation activation,
            @Schema(nullable = true, description = "Required for CONTRACT, 1 to 100 characters; not allowed for QR") @Size(max = 100) String contractNo) {}

    @Schema(name = "LicenseCourse")
    record LicenseCourse(
            @Schema(requiredMode = Schema.RequiredMode.REQUIRED) UUID id,
            @Schema(requiredMode = Schema.RequiredMode.REQUIRED) String code,
            @Schema(nullable = true) String title) {}

    @Schema(name = "SeatHolder")
    record SeatHolder(
            @Schema(requiredMode = Schema.RequiredMode.REQUIRED) UUID userId,
            @Schema(nullable = true) String displayName,
            @Schema(nullable = true) String email,
            @Schema(requiredMode = Schema.RequiredMode.REQUIRED) List<UUID> courseIds,
            @Schema(requiredMode = Schema.RequiredMode.REQUIRED) Instant assignedAt) {}

    @Schema(name = "OrgLicenseView")
    record LicenseView(
            @Schema(requiredMode = Schema.RequiredMode.REQUIRED) UUID id,
            @Schema(requiredMode = Schema.RequiredMode.REQUIRED) UUID orgId,
            @Schema(requiredMode = Schema.RequiredMode.REQUIRED) int seats,
            @Schema(requiredMode = Schema.RequiredMode.REQUIRED) int seatsUsed,
            @Schema(requiredMode = Schema.RequiredMode.REQUIRED) int months,
            @Schema(requiredMode = Schema.RequiredMode.REQUIRED) long priceVnd,
            @Schema(requiredMode = Schema.RequiredMode.REQUIRED) Activation activation,
            @Schema(nullable = true) String contractNo,
            @Schema(requiredMode = Schema.RequiredMode.REQUIRED, description = "PENDING_PAYMENT, ACTIVE or CANCELLED") String status,
            @Schema(nullable = true) Instant validFrom,
            @Schema(nullable = true) Instant validUntil,
            @Schema(requiredMode = Schema.RequiredMode.REQUIRED) Instant createdAt,
            @Schema(requiredMode = Schema.RequiredMode.REQUIRED) List<LicenseCourse> courses,
            @Schema(nullable = true, description = "The order of a QR licence") String orderCode,
            @Schema(requiredMode = Schema.RequiredMode.REQUIRED, description = "Who holds a seat now; filled for the organisation's own reading, empty in the administrators' list") List<SeatHolder> holders) {}

    @Schema(name = "OrgLicenseCreated")
    record LicenseCreated(
            @Schema(requiredMode = Schema.RequiredMode.REQUIRED) LicenseView license,
            @Schema(nullable = true, description = "QR: the transfer note the payer puts in the bank transfer") String transferNote,
            @Schema(nullable = true) String qrPayload,
            @Schema(nullable = true) PayTo payTo) {}

    @Schema(name = "AssignSeatRequest")
    record AssignSeatRequest(
            @Schema(requiredMode = Schema.RequiredMode.REQUIRED, description = "A member of the organisation") @NotNull UUID userId,
            @Schema(requiredMode = Schema.RequiredMode.REQUIRED, description = "A subset of the licence's courses") @NotEmpty @Size(max = 100) List<@NotNull UUID> courseIds) {}
}
