package com.english9x.org.internal;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.english9x.support.TestPostgres;
import javax.sql.DataSource;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.datasource.DriverManagerDataSource;

/** Startup refuses any application login that would bypass RLS; only e9x_app_login passes. */
class AppRoleGuardIT {

    static final String DB = TestPostgres.createDatabase();

    @BeforeAll
    static void migrate() {
        Flyway.configure()
                .dataSource(TestPostgres.jdbcUrl(DB), TestPostgres.OWNER, TestPostgres.OWNER_PASSWORD)
                .locations("classpath:db/migration")
                .load()
                .migrate();
    }

    @Test
    void theRuntimeLoginPasses() {
        DataSource app = new DriverManagerDataSource(TestPostgres.jdbcUrl(DB), TestPostgres.APP_LOGIN, TestPostgres.APP_PASSWORD);
        assertDoesNotThrow(() -> AppRoleGuard.verify(app));
    }

    @Test
    void aSuperuserIsRefused() {
        IllegalStateException e = assertThrows(IllegalStateException.class, () -> AppRoleGuard.verify(TestPostgres.superuser(DB)));
        assertTrue(e.getMessage().contains("BYPASSRLS"), e.getMessage());
    }

    @Test
    void theTableOwnerIsRefused() {
        IllegalStateException e = assertThrows(IllegalStateException.class, () -> AppRoleGuard.verify(TestPostgres.owner(DB)));
        assertTrue(e.getMessage().contains("owns tables"), e.getMessage());
    }
}
