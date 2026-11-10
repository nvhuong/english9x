package com.english9x.billing.payment;

import com.english9x.billing.payment.PaymentAccountDtos.PaymentAccountRequest;
import com.english9x.billing.payment.PaymentAccountDtos.PaymentAccountView;
import jakarta.validation.Valid;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;

/** The account that receives payments. SYS_ADMIN only. */
@RestController
class PaymentAccountController {

    private final PaymentAccountService accounts;

    PaymentAccountController(PaymentAccountService accounts) {
        this.accounts = accounts;
    }

    /** The active account; 404 when none is configured yet. */
    @GetMapping("/api/admin/payment-account")
    PaymentAccountView current() {
        return accounts.current();
    }

    /** Makes this the one active account (the previous one is retired). */
    @PutMapping("/api/admin/payment-account")
    PaymentAccountView replace(@Valid @RequestBody PaymentAccountRequest body) {
        return accounts.replace(body);
    }
}
