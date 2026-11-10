package com.english9x.billing.payment;

import com.english9x.billing.payment.PaymentAccountDtos.PaymentAccountView;
import java.util.Optional;
import java.util.UUID;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

/** Every statement runs under the caller's Row-Level Security (anybody signed in reads the active account, a SYS_ADMIN writes). */
@Repository
class PaymentAccountRepository {

    private final JdbcTemplate jdbc;

    PaymentAccountRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    Optional<PaymentAccountView> active() {
        return jdbc.query("select bank_bin, account_no, account_name, updated_at from payment_accounts where active",
                (rs, i) -> new PaymentAccountView(rs.getString("bank_bin").strip(), rs.getString("account_no"), rs.getString("account_name"), rs.getTimestamp("updated_at").toInstant()))
                .stream().findFirst();
    }

    /** Retires the active account (if any). */
    void deactivate() {
        jdbc.update("update payment_accounts set active = false, updated_at = now() where active");
    }

    UUID insert(String bankBin, String accountNo, String accountName) {
        return jdbc.queryForObject("insert into payment_accounts(bank_bin, account_no, account_name) values (?, ?, ?) returning id", UUID.class, bankBin, accountNo, accountName);
    }
}
