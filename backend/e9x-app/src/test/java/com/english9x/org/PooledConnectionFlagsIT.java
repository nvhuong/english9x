package com.english9x.org;

import static org.junit.jupiter.api.Assertions.assertEquals;

import com.english9x.org.api.CurrentContextHolder;
import com.english9x.support.TestPostgres;
import com.zaxxer.hikari.HikariDataSource;
import java.util.Map;
import java.util.UUID;
import javax.sql.DataSource;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

/** Privilege flags and org context must never survive into the next transaction on a pooled connection. */
@SpringBootTest(
        webEnvironment = SpringBootTest.WebEnvironment.NONE,
        properties = "spring.datasource.hikari.maximum-pool-size=1")
class PooledConnectionFlagsIT {

    static final String DB = TestPostgres.createDatabase();

    @DynamicPropertySource
    static void database(DynamicPropertyRegistry registry) {
        TestPostgres.connect(registry, DB);
    }

    @Autowired
    JdbcTemplate jdbc;

    @Autowired
    PlatformTransactionManager tm;

    @Autowired
    DataSource dataSource;

    private Map<String, Object> snapshot() {
        return jdbc.queryForMap("""
                select pg_backend_pid() as pid,
                       coalesce(current_setting('app.org_id', true), '') as org_id,
                       coalesce(current_setting('app.user_id', true), '') as user_id,
                       coalesce(current_setting('app.e9x_staff', true), '') as staff,
                       coalesce(current_setting('app.org_admin', true), '') as org_admin
                """);
    }

    @Test
    void nextTransactionCarriesNoFlags() {
        JdbcTemplate su = new JdbcTemplate(TestPostgres.superuser(DB));
        UUID org = UUID.randomUUID();
        UUID admin = UUID.randomUUID();
        su.update("insert into organizations(id,name,type) values (?,'A','CENTER')", org);
        su.update("insert into users(id,email) values (?,'admin@x.test')", admin);
        su.update("insert into org_memberships(org_id,user_id) values (?,?)", org, admin);
        su.update("insert into role_grants(user_id,org_id,exam_code,role) values (?,?,null,'ORG_ADMIN')", admin, org);

        // the proof is only worth anything if both transactions really run on the same physical connection
        assertEquals(1, ((HikariDataSource) dataSource).getMaximumPoolSize());
        TransactionTemplate tx = new TransactionTemplate(tm);

        Map<String, Object> first;
        try (var scope = CurrentContextHolder.bind(admin, org)) {
            first = tx.execute(st -> snapshot());
        }
        assertEquals(org.toString(), first.get("org_id"));
        assertEquals("on", first.get("org_admin"));

        Map<String, Object> second = tx.execute(st -> snapshot()); // no context bound
        assertEquals(first.get("pid"), second.get("pid"), "both transactions must share one physical connection");
        assertEquals("", second.get("org_id"));
        assertEquals("", second.get("user_id"));
        assertEquals("", second.get("staff"));
        assertEquals("", second.get("org_admin"));

        Map<String, Object> autocommit = snapshot(); // and outside any transaction
        assertEquals(first.get("pid"), autocommit.get("pid"));
        assertEquals("", autocommit.get("org_admin"));
        assertEquals("", autocommit.get("org_id"));
    }

    @Test
    void aFlaggedTransactionThatRollsBackLeavesNoFlagAndNoRow() {
        JdbcTemplate su = new JdbcTemplate(TestPostgres.superuser(DB));
        UUID org = UUID.randomUUID();
        UUID admin = UUID.randomUUID();
        UUID doomed = UUID.randomUUID();
        su.update("insert into organizations(id,name,type) values (?,'Rollback','CENTER')", org);
        su.update("insert into users(id,email) values (?,?)", admin, admin + "@x.test");
        su.update("insert into org_memberships(org_id,user_id) values (?,?)", org, admin);
        su.update("insert into role_grants(user_id,org_id,exam_code,role) values (?,?,null,'ORG_ADMIN')", admin, org);

        TransactionTemplate tx = new TransactionTemplate(tm);
        Map<String, Object> first;
        try (var scope = CurrentContextHolder.bind(admin, org)) {
            first = tx.execute(st -> {
                Map<String, Object> inside = snapshot();
                jdbc.update("insert into classes(id,owner_org_id,name) values (?,?,'doomed')", doomed, org);
                st.setRollbackOnly();
                return inside;
            });
        }
        assertEquals("on", first.get("org_admin"), "the transaction really was flagged");

        Map<String, Object> next = tx.execute(st -> snapshot()); // no context bound
        assertEquals(first.get("pid"), next.get("pid"), "the same physical connection");
        assertEquals("", next.get("org_admin"));
        assertEquals("", next.get("org_id"));
        assertEquals("", next.get("user_id"));
        assertEquals(0, su.queryForObject("select count(*) from classes where id = ?", Integer.class, doomed), "the write went with it");
        assertEquals("", snapshot().get("org_admin"), "and outside any transaction");
    }

    @Test
    void aFlagSetAtSessionLevelByBuggyCodeIsStillClearedAtTheNextTransactionStart() {
        TransactionTemplate tx = new TransactionTemplate(tm);
        tx.execute(st -> {
            // simulates code that wrongly uses is_local=false: the flags would outlive this transaction
            jdbc.queryForList("select set_config('app.e9x_staff','on',false), set_config('app.org_admin','on',false), "
                    + "set_config('app.org_id', gen_random_uuid()::text, false)");
            return null;
        });
        Map<String, Object> leaked = jdbc.queryForMap("select current_setting('app.e9x_staff', true) as staff");
        assertEquals("on", leaked.get("staff"), "precondition: the session-level value survived the transaction");

        try {
            Map<String, Object> next = tx.execute(st -> snapshot());
            assertEquals("", next.get("staff"));
            assertEquals("", next.get("org_admin"));
            assertEquals("", next.get("org_id"));
        } finally { // leave the shared single connection clean for the other test
            jdbc.queryForList("select set_config('app.e9x_staff','',false), set_config('app.org_admin','',false), "
                    + "set_config('app.org_id','',false)");
        }
    }
}
