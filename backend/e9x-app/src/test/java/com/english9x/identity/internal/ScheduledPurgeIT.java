package com.english9x.identity.internal;

import static org.junit.jupiter.api.Assertions.assertEquals;

import com.english9x.support.TestPostgres;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

/** The scheduled housekeeping bean itself enforces e9x.auth.no-consent-retention (default 7 days), not just a function nobody calls. */
@SpringBootTest
class ScheduledPurgeIT {

    static final String DB = TestPostgres.createDatabase();

    @DynamicPropertySource
    static void database(DynamicPropertyRegistry registry) {
        TestPostgres.connect(registry, DB);
    }

    @Autowired
    IdentityStartupChecks.Purge scheduled;

    private final JdbcTemplate su = new JdbcTemplate(TestPostgres.superuser(DB));

    private String abandonedDaysAgo(int days) {
        UUID id = UUID.randomUUID();
        String email = "abandoned-" + id + "@example.test";
        su.update("insert into users(id, email, display_name, created_at) values (?, ?, 'Google Name', now() - make_interval(days => ?))", id, email, days);
        su.update("insert into auth_identities(user_id, provider, subject, email) values (?, 'google', ?, ?)", id, "sub-" + id, email);
        return email;
    }

    private int accounts(String email) {
        return su.queryForObject("select count(*) from users where email = ?", Integer.class, email);
    }

    @Test
    void theScheduledRunDeletesAnExpiredNoConsentAccountAndKeepsOneWithinRetention() {
        String expired = abandonedDaysAgo(8);
        String within = abandonedDaysAgo(6);
        scheduled.purge();
        assertEquals(0, accounts(expired), "past the retention: gone");
        assertEquals(1, accounts(within), "within the retention: kept");
    }
}
