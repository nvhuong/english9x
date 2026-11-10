package com.english9x.billing.payment;

import com.english9x.billing.BillingIT;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/** Shared fixtures of the P5.2 tests: the one active payment account and orders placed through the real endpoint. */
abstract class PaymentIT extends BillingIT {

    /** Access tokens are signed with real time and checked against the application clock: another test class may have moved it. */
    @org.junit.jupiter.api.BeforeEach
    void realTime() {
        clock.set(java.time.Instant.now());
    }

    protected static final String BIN = "970436";
    protected static final String ACCOUNT_NO = "1234567890123";

    /** Makes the given account the only active one (written as the superuser: the admin endpoint has its own test). */
    protected void account(String bin, String accountNo) {
        su.update("update payment_accounts set active = false where active");
        su.update("insert into payment_accounts(bank_bin, account_no, account_name) values (?, ?, 'ENGLISH9X JSC')", bin, accountNo);
    }

    protected Res placeOrder(UUID user, UUID plan, UUID... courses) {
        return post("/api/orders", Map.of("planId", plan, "courseIds", List.of(courses)), user, null);
    }
}
