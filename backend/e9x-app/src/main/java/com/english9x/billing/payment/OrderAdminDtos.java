package com.english9x.billing.payment;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.Size;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

final class OrderAdminDtos {

    private OrderAdminDtos() {}

    @Schema(name = "ConfirmOrderRequest")
    record ConfirmOrderRequest(
            @Schema(nullable = true, description = "The bank's transaction reference, if the administrator has it (at most 100 characters)") @Size(max = 100) String bankRef) {}

    @Schema(name = "RejectOrderRequest")
    record RejectOrderRequest(
            @Schema(requiredMode = Schema.RequiredMode.REQUIRED, description = "Why the payment was not accepted: 1 to 500 characters, shown to the learner") String reason) {}

    @Schema(name = "AdminOrderView")
    record AdminOrderView(
            @Schema(requiredMode = Schema.RequiredMode.REQUIRED) String code,
            @Schema(requiredMode = Schema.RequiredMode.REQUIRED, description = "PENDING_PAYMENT, CONFIRMED, REJECTED or EXPIRED") String status,
            @Schema(requiredMode = Schema.RequiredMode.REQUIRED) long amountVnd,
            @Schema(requiredMode = Schema.RequiredMode.REQUIRED) int planMonths,
            @Schema(requiredMode = Schema.RequiredMode.REQUIRED) Instant createdAt,
            @Schema(nullable = true) Instant confirmedAt,
            @Schema(nullable = true) String bankRef,
            @Schema(nullable = true) String rejectReason,
            @Schema(requiredMode = Schema.RequiredMode.REQUIRED, description = "What the payer should have put in the transfer, to match it against the bank statement") String transferNote,
            @Schema(requiredMode = Schema.RequiredMode.REQUIRED) UUID userId,
            @Schema(nullable = true) String userEmail,
            @Schema(nullable = true) String userName,
            @Schema(requiredMode = Schema.RequiredMode.REQUIRED) List<String> courseCodes) {}
}
