package com.english9x.billing.payment;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import java.time.Instant;

final class PaymentAccountDtos {

    private PaymentAccountDtos() {}

    @Schema(name = "PaymentAccountRequest")
    record PaymentAccountRequest(
            @Schema(requiredMode = Schema.RequiredMode.REQUIRED, description = "The bank's 6-digit NAPAS BIN") @NotBlank @Pattern(regexp = "[0-9]{6}") String bankBin,
            @Schema(requiredMode = Schema.RequiredMode.REQUIRED, description = "1 to 19 letters or digits") @NotBlank @Pattern(regexp = "[A-Za-z0-9]{1,19}") String accountNo,
            @Schema(requiredMode = Schema.RequiredMode.REQUIRED, description = "Name of the account holder, shown to the payer") @NotBlank @Size(max = 100) String accountName) {}

    @Schema(name = "PaymentAccountView")
    record PaymentAccountView(
            @Schema(requiredMode = Schema.RequiredMode.REQUIRED) String bankBin,
            @Schema(requiredMode = Schema.RequiredMode.REQUIRED) String accountNo,
            @Schema(requiredMode = Schema.RequiredMode.REQUIRED) String accountName,
            @Schema(requiredMode = Schema.RequiredMode.REQUIRED) Instant updatedAt) {}
}
