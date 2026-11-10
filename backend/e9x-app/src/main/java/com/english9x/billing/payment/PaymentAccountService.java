package com.english9x.billing.payment;

import com.english9x.audit.api.AuditLog;
import com.english9x.billing.internal.BillingProblems;
import com.english9x.billing.payment.PaymentAccountDtos.PaymentAccountRequest;
import com.english9x.billing.payment.PaymentAccountDtos.PaymentAccountView;
import com.english9x.org.api.Authz;
import com.english9x.org.api.Role;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * The one bank account that receives payments. Reading the active one is for everybody signed in (the payer needs it, the QR carries it);
 * changing it is a real SYS_ADMIN's, audited with the bank BIN only: never the account number.
 */
@Service
class PaymentAccountService {

    private final PaymentAccountRepository repo;
    private final Authz authz;
    private final AuditLog audit;

    PaymentAccountService(PaymentAccountRepository repo, Authz authz, AuditLog audit) {
        this.repo = repo;
        this.authz = authz;
        this.audit = audit;
    }

    /** The active account, as the caller may see it; empty when none is configured. */
    @Transactional(readOnly = true)
    Optional<PaymentAccountView> active() {
        return repo.active();
    }

    @Transactional(readOnly = true)
    PaymentAccountView current() {
        authz.require(Role.SYS_ADMIN, Optional.empty());
        return repo.active().orElseThrow(BillingProblems::notFound);
    }

    @Transactional
    PaymentAccountView replace(PaymentAccountRequest r) {
        authz.require(Role.SYS_ADMIN, Optional.empty());
        Optional<PaymentAccountView> before = repo.active();
        repo.deactivate();
        try {
            var id = repo.insert(r.bankBin(), r.accountNo(), r.accountName().strip());
            Map<String, Object> detail = new LinkedHashMap<>();
            detail.put("bankBin", r.bankBin());
            before.ifPresent(b -> detail.put("previousBankBin", b.bankBin()));
            audit.record("payment-account.update", "payment-account", id, detail);
        } catch (DuplicateKeyException e) {
            throw BillingProblems.paymentAccountChanged();
        }
        return repo.active().orElseThrow();
    }
}
