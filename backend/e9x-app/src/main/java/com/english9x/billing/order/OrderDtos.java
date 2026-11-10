package com.english9x.billing.order;

import com.english9x.billing.payment.PaymentInstructions.PayTo;
import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

final class OrderDtos {

    private OrderDtos() {}

    @Schema(name = "CreateOrderRequest")
    record CreateOrderRequest(
            @Schema(requiredMode = Schema.RequiredMode.REQUIRED) @NotNull UUID planId,
            @Schema(requiredMode = Schema.RequiredMode.REQUIRED, description = "Published English9x courses, at most the plan's maxCourses") @NotEmpty @Size(max = 100) List<@NotNull UUID> courseIds) {}

    @Schema(name = "OrderCreated")
    record OrderCreated(
            @Schema(requiredMode = Schema.RequiredMode.REQUIRED, description = "6 characters, A-Z and 0-9") String orderCode,
            @Schema(requiredMode = Schema.RequiredMode.REQUIRED, description = "The price of the plan when the order was placed") long amountVnd,
            @Schema(requiredMode = Schema.RequiredMode.REQUIRED, description = "Text the payer puts in the bank transfer: E9X, the order code and the course codes (the order code alone when too long)") String transferNote,
            @Schema(nullable = true, description = "The VietQR text for the QR code. Null while no payment account is configured") String qrPayload,
            @Schema(nullable = true, description = "URL of the QR image (PNG, 512 px). Null while no payment account is configured") String qrPngUrl,
            @Schema(nullable = true, description = "The bank account to pay into. Null while no payment account is configured") PayTo payTo) {}

    @Schema(name = "OrderCourse")
    record OrderCourse(UUID id, String code) {}

    @Schema(name = "OrderView")
    record OrderView(
            @Schema(requiredMode = Schema.RequiredMode.REQUIRED) String code,
            @Schema(requiredMode = Schema.RequiredMode.REQUIRED, description = "PENDING_PAYMENT, CONFIRMED, REJECTED or EXPIRED") String status,
            @Schema(requiredMode = Schema.RequiredMode.REQUIRED) long amountVnd,
            @Schema(nullable = true, description = "The plan of a personal order; null for the order of an organisation licence") UUID planId,
            @Schema(nullable = true, description = "The organisation licence this order pays for; null for a plan order") UUID licenseId,
            @Schema(requiredMode = Schema.RequiredMode.REQUIRED) int planMonths,
            @Schema(requiredMode = Schema.RequiredMode.REQUIRED) Instant createdAt,
            @Schema(requiredMode = Schema.RequiredMode.REQUIRED) String transferNote,
            @Schema(requiredMode = Schema.RequiredMode.REQUIRED) List<OrderCourse> courses,
            @Schema(nullable = true, description = "Why the order was not confirmed (REJECTED)") String rejectReason,
            @Schema(nullable = true, description = "The VietQR text; only while the order is PENDING_PAYMENT and an account is configured") String qrPayload,
            @Schema(nullable = true, description = "URL of the QR image; same rule as qrPayload") String qrPngUrl,
            @Schema(nullable = true, description = "The bank account to pay into; same rule as qrPayload") PayTo payTo) {}
}
