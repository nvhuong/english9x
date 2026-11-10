package com.english9x.platform.jobs;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.english9x.support.TestPostgres;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.dao.DataAccessException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * The privilege model of the queue. Chosen design: no BYPASSRLS anywhere. The worker role e9x_worker has explicit
 * policies on `jobs` and no grant on any other table; e9x_app (requests) may only INSERT QUEUED rows.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.NONE, properties = "e9x.jobs.poll-interval=1h")
class JobRolesIT {

    static final String DB = TestPostgres.createDatabase();

    @DynamicPropertySource
    static void database(DynamicPropertyRegistry registry) {
        TestPostgres.connect(registry, DB);
    }

    @Autowired WorkerDatabase workerDatabase;
    @Autowired JdbcTemplate app; // the primary pool: e9x_app_login
    @Autowired PlatformTransactionManager tm;

    final JdbcTemplate su = new JdbcTemplate(TestPostgres.superuser(DB));
    final JdbcTemplate worker = new JdbcTemplate(TestPostgres.worker(DB));

    @BeforeEach
    void seed() {
        su.update("delete from jobs");
        su.update("insert into jobs(id,type,payload,status,run_at) values (?, 'test_ok', '{\"pii\":\"secret\"}', 'QUEUED', now())", UUID.randomUUID());
    }

    private static void denied(Runnable r, String what) {
        DataAccessException e = assertThrows(DataAccessException.class, r::run, what);
        String m = String.valueOf(e.getMostSpecificCause().getMessage());
        assertTrue(m.contains("permission denied") || m.contains("row-level security"), what + ": " + m);
    }

    private void inTx(Runnable r) {
        new TransactionTemplate(tm).executeWithoutResult(s -> r.run());
    }

    @Test
    void theWorkerPoolConnectsAsTheWorkerLoginAndIsAnOrdinaryRole() {
        assertEquals("e9x_worker_login", workerDatabase.raw().queryForObject("select current_user", String.class));
        assertEquals("e9x_worker_login", workerDatabase.raw().queryForObject("select session_user", String.class));
        assertFalse(worker.queryForObject("select rolsuper from pg_roles where rolname = current_user", Boolean.class));
        assertFalse(worker.queryForObject("select rolbypassrls from pg_roles where rolname = current_user", Boolean.class));
        assertFalse(worker.queryForObject("select rolcreaterole or rolcreatedb or rolreplication from pg_roles where rolname = current_user", Boolean.class));
        assertEquals(0, worker.queryForObject(
                "select count(*) from pg_class c where c.relnamespace = 'public'::regnamespace and c.relkind in ('r','p') and pg_has_role(current_user, c.relowner, 'MEMBER')",
                Integer.class), "owns nothing and is not a member of any table owner");
        assertTrue(worker.queryForObject("select pg_has_role(current_user, 'e9x_worker', 'MEMBER')", Boolean.class));
        assertFalse(worker.queryForObject("select pg_has_role(current_user, 'e9x_app', 'MEMBER') or pg_has_role(current_user, 'e9x_grader', 'MEMBER')", Boolean.class));
    }

    @Test
    void theWorkerReadsAndUpdatesJobsOnly() {
        assertEquals(1, worker.queryForObject("select count(*) from jobs", Integer.class), "sees every job (explicit policy)");
        assertEquals(1, worker.update("update jobs set status = 'RUNNING', attempts = 1, locked_until = now(), lease_token = ?", UUID.randomUUID()));

        // not the payload, not the type, not new rows, not deletion
        denied(() -> worker.update("update jobs set payload = '{}'"), "payload is read-only");
        denied(() -> worker.update("update jobs set type = 'other'"), "type is read-only");
        denied(() -> worker.update("insert into jobs(id,type,payload,status,run_at) values (gen_random_uuid(),'x','{}','QUEUED',now())"), "insert");
        // it deletes only finished jobs older than a day (retention, policy jobs_worker_purge): this one is RUNNING
        assertEquals(0, worker.update("delete from jobs"), "delete reaches nothing that is not long finished");

        // and nothing else of the system, until a later migration says so
        for (String table : new String[] {"role_grants", "org_memberships", "users", "organizations", "entitlements", "classes", "questions", "exam_attempts"}) {
            denied(() -> worker.queryForList("select * from " + table), "worker must not read " + table);
        }
        denied(() -> worker.update("create table sneaky(id int)"), "create table");
        denied(() -> worker.queryForList("select * from flyway_schema_history"), "flyway history");
    }

    @Test
    void aForgedContextSettingDoesNotOpenTenantTablesToTheWorker() {
        su.update("insert into organizations(id,name,type) values ('a1a1a1a1-0000-0000-0000-00000000000a','A','CENTER')");
        try {
            denied(() -> worker.execute((java.sql.Connection c) -> {
                try (var st = c.createStatement()) {
                    st.execute("select set_config('app.org_id','a1a1a1a1-0000-0000-0000-00000000000a',false)");
                    st.execute("select * from organizations"); // one session: the GUC is set when this runs
                } catch (java.sql.SQLException e) {
                    throw new org.springframework.jdbc.UncategorizedSQLException("forged", "select", e);
                }
                return null;
            }), "a forged org setting does not open tenant tables");
        } finally {
            su.update("delete from organizations");
        }
    }

    @Test
    void requestsMayOnlyInsertQueuedJobsAndCannotReadOrTamper() {
        UUID id = UUID.randomUUID();
        inTx(() -> assertEquals(1, app.update(
                "insert into jobs(id,type,payload,status,run_at) values (?, 'test_ok', '{\"a\":1}'::jsonb, 'QUEUED', ?)", id,
                Timestamp.from(Instant.now()))));
        assertEquals(2, su.queryForObject("select count(*) from jobs", Integer.class));

        denied(() -> inTx(() -> app.queryForList("select * from jobs")), "no read of anyone's payload");
        denied(() -> inTx(() -> app.queryForList("select payload from jobs")), "no read of the payload column");
        denied(() -> inTx(() -> app.update("update jobs set payload = '{}'")), "no tampering");
        denied(() -> inTx(() -> app.update("update jobs set status = 'FAILED'")), "no tampering with state");
        denied(() -> inTx(() -> app.update("delete from jobs")), "no deletion");
        denied(() -> inTx(() -> app.update(
                "insert into jobs(id,type,payload,status,run_at,attempts) values (gen_random_uuid(),'x','{}','SUCCEEDED',now(),1)")),
                "a request cannot forge a finished job");
        denied(() -> inTx(() -> app.update(
                "insert into jobs(id,type,payload,status,run_at,locked_until,lease_token) values (gen_random_uuid(),'x','{}','RUNNING',now(),now(),gen_random_uuid())")),
                "a request cannot forge a running job");
        assertEquals(2, su.queryForObject("select count(*) from jobs", Integer.class));
    }

    @Test
    void rowLevelSecurityIsEnabledOnJobs() {
        assertTrue(su.queryForObject("select relrowsecurity from pg_class where oid = 'public.jobs'::regclass", Boolean.class));
        assertEquals("e9x_owner", su.queryForObject("select tableowner from pg_tables where tablename = 'jobs'", String.class));
    }

    // ---------------------------------------------------------------- the startup guard

    @Test
    void theGuardAcceptsOnlyTheWorkerLogin() {
        assertDoesNotThrow(() -> WorkerRoleGuard.verify(TestPostgres.worker(DB)));
        assertThrows(IllegalStateException.class, () -> WorkerRoleGuard.verify(TestPostgres.superuser(DB)), "superuser");
        assertThrows(IllegalStateException.class, () -> WorkerRoleGuard.verify(TestPostgres.owner(DB)), "table owner");
        IllegalStateException appLogin = assertThrows(IllegalStateException.class, () -> WorkerRoleGuard.verify(TestPostgres.app(DB)));
        assertTrue(appLogin.getMessage().contains("e9x_worker"), appLogin.getMessage());
    }

    @Test
    void theGuardRefusesAWorkerThatCouldBypassRlsOrReachTheAppRole() {
        JdbcTemplate boot = new JdbcTemplate(TestPostgres.superuser("bootstrap"));
        // a login that is a worker but ALSO bypasses RLS, and one that is a worker and also an app member
        boot.execute("DROP ROLE IF EXISTS w_bypass");
        boot.execute("DROP ROLE IF EXISTS w_both");
        boot.execute("CREATE ROLE w_bypass LOGIN BYPASSRLS PASSWORD 'x' IN ROLE e9x_worker");
        boot.execute("CREATE ROLE w_both LOGIN PASSWORD 'x' IN ROLE e9x_worker, e9x_app");
        try {
            var bypass = new org.springframework.jdbc.datasource.DriverManagerDataSource(TestPostgres.jdbcUrl(DB), "w_bypass", "x");
            var both = new org.springframework.jdbc.datasource.DriverManagerDataSource(TestPostgres.jdbcUrl(DB), "w_both", "x");
            assertTrue(assertThrows(IllegalStateException.class, () -> WorkerRoleGuard.verify(bypass)).getMessage().contains("BYPASSRLS"));
            assertThrows(IllegalStateException.class, () -> WorkerRoleGuard.verify(both));
        } finally {
            boot.execute("DROP ROLE w_bypass");
            boot.execute("DROP ROLE w_both");
        }
    }

    @Test
    void workerJdbcRefusesAnyThreadThatIsNotRunningAJob() {
        assertThrows(IllegalStateException.class, () -> workerDatabase.jdbc());
        assertThrows(IllegalStateException.class, () -> workerDatabase.transaction());
    }
}
