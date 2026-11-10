package com.english9x.billing.payment;

import com.english9x.domain.billing.VietQr;
import com.english9x.domain.billing.VietQrRequest;
import io.swagger.v3.oas.annotations.media.Schema;
import java.util.Optional;
import org.springframework.stereotype.Component;

/**
 * How a payer pays an order: the active bank account and the VietQR text for one order. The order module asks here; nothing else about payment
 * is shared. The transfer note is the one the order already shows (TransferNote.build with e9x.billing.transfer-note-max-length), so the QR and
 * the text the payer copies can never disagree.
 */
@Component
public class PaymentInstructions {

    /** Where the money goes, as it is shown to the payer. */
    @Schema(name = "PayTo")
    public record PayTo(
            @Schema(requiredMode = Schema.RequiredMode.REQUIRED) String bankBin,
            @Schema(requiredMode = Schema.RequiredMode.REQUIRED) String accountNo,
            @Schema(requiredMode = Schema.RequiredMode.REQUIRED) String accountName) {}

    private final PaymentAccountService accounts;

    PaymentInstructions(PaymentAccountService accounts) {
        this.accounts = accounts;
    }

    /** The active account; empty while none is configured. */
    public Optional<PayTo> payTo() {
        return accounts.active().map(a -> new PayTo(a.bankBin(), a.accountNo(), a.accountName()));
    }

    public String qrPayload(PayTo to, long amountVnd, String transferNote) {
        return VietQr.payload(new VietQrRequest(to.bankBin(), to.accountNo(), amountVnd, transferNote));
    }
}
