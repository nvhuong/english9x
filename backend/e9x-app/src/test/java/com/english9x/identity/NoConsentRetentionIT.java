package com.english9x.identity;

import static org.junit.jupiter.api.Assertions.assertEquals;

import com.english9x.identity.api.AccountHousekeeping;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.test.context.TestPropertySource;

/** e9x.auth.no-consent-retention is what the purge goes by. Configured to 30 days here (the default is 7). */
@TestPropertySource(properties = "e9x.auth.no-consent-retention=30d")
class NoConsentRetentionIT extends IdentityIT {

    @Autowired
    AccountHousekeeping housekeeping;

    private String abandonedDaysAgo(int days) {
        String email = uniqueEmail();
        UUID id = UUID.randomUUID();
        su.update("insert into users(id, email, created_at) values (?, ?, now() - make_interval(days => ?))", id, email, days);
        su.update("insert into auth_identities(user_id, provider, subject, email) values (?, 'google', ?, ?)", id, "sub-" + id, email);
        return email;
    }

    private int accounts(String email) {
        return su.queryForObject("select count(*) from users where email = ?", Integer.class, email);
    }

    @Test
    void theConfiguredRetentionDecides() {
        String kept = abandonedDaysAgo(8);
        String old = abandonedDaysAgo(31);
        assertEquals(1, housekeeping.purgeAccountsWithoutConsent());
        assertEquals(1, accounts(kept));
        assertEquals(0, accounts(old));
        assertEquals(0, su.queryForObject("select count(*) from auth_identities where email = ?", Integer.class, old), "its identity goes with it");
    }
}
