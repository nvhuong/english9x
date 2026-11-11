package com.english9x.platform;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.english9x.E9xApplication;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.UUID;
import org.flywaydb.core.Flyway;
import org.flywaydb.core.api.FlywayException;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.boot.builder.SpringApplicationBuilder;
import org.springframework.context.ConfigurableApplicationContext;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.testcontainers.containers.Container;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.utility.MountableFile;

/**
 * The real backup and restore path of infra/backup, final review I4 (spec §15, §16.3): backup.sh on a database Flyway built, restore.sh into a fresh
 * volume prepared by 01-roles.sh, then Flyway and the whole application on the restored data. Each "volume" is its own container, started the way Compose
 * starts postgres. The object store half is W10's (plan). {@code aRestoreAsTheSuperuserCannotMigrate} is the measurement that makes restore.sh refuse it.
 */
class BackupRestoreIT {

    static final String OWNER_PW = "owner-restore-test";
    static final String APP_PW = "app-restore-test";
    static final String WORKER_PW = "worker-restore-test";
    static final String GRADER_PW = "grader-restore-test";
    static final UUID USER = UUID.randomUUID();

    @TempDir
    Path tmp;

    /** A new, empty volume: the init script has run, and nothing else. */
    private static PostgreSQLContainer<?> freshVolume() {
        Path infra = Path.of("..", "..", "infra").toAbsolutePath().normalize();
        PostgreSQLContainer<?> c = new PostgreSQLContainer<>("postgres:16")
                .withDatabaseName("e9x")
                .withUsername("e9x")
                .withPassword("superuser-restore-test")
                .withEnv("E9X_OWNER_PASSWORD", OWNER_PW)
                .withEnv("E9X_APP_PASSWORD", APP_PW)
                .withEnv("E9X_WORKER_PASSWORD", WORKER_PW)
                .withEnv("E9X_GRADER_PASSWORD", GRADER_PW)
                .withCopyFileToContainer(MountableFile.forHostPath(infra.resolve("postgres/init/01-roles.sh"), 0755), "/docker-entrypoint-initdb.d/01-roles.sh")
                .withCopyFileToContainer(MountableFile.forHostPath(infra.resolve("backup/backup.sh"), 0755), "/usr/local/bin/backup.sh")
                .withCopyFileToContainer(MountableFile.forHostPath(infra.resolve("backup/restore.sh"), 0755), "/usr/local/bin/restore.sh");
        c.start();
        return c;
    }

    private static Flyway flyway(PostgreSQLContainer<?> db, String... extraLocations) {
        String[] locations = new String[extraLocations.length + 1];
        locations[0] = "classpath:db/migration";
        System.arraycopy(extraLocations, 0, locations, 1, extraLocations.length);
        return Flyway.configure().dataSource(db.getJdbcUrl(), "e9x_owner", OWNER_PW).locations(locations).load();
    }

    private static JdbcTemplate superuser(PostgreSQLContainer<?> db) {
        return new JdbcTemplate(new DriverManagerDataSource(db.getJdbcUrl(), db.getUsername(), db.getPassword()));
    }

    /** A database with data in it, dumped by the real script as the login Compose gives it: the superuser. Returns the dump on the host. */
    private Path backedUpDatabase() throws Exception {
        try (PostgreSQLContainer<?> source = freshVolume()) {
            flyway(source).migrate();
            JdbcTemplate su = superuser(source);
            su.update("insert into users(id,email,email_verified_at,consent_at) values (?,'restored@x.test',now(),now())", USER);
            su.update("insert into audit_log(actor_id,action,target_type,target_id) values (?,'role.change','user',?)", USER, USER.toString());
            Container.ExecResult backup = source.execInContainer("bash", "-c",
                    "PGHOST=localhost PGDATABASE=e9x PGUSER=e9x PGPASSWORD=superuser-restore-test BACKUP_DIR=/backups /usr/local/bin/backup.sh && ls /backups");
            assertEquals(0, backup.getExitCode(), backup.getStderr());
            String name = backup.getStdout().lines().filter(l -> l.matches("pg-.*\\.sql\\.gz")).findFirst().orElseThrow();
            Path host = tmp.resolve(name);
            source.copyFileFromContainer("/backups/" + name, host.toString());
            return host;
        }
    }

    private static Container.ExecResult restore(PostgreSQLContainer<?> target, String user) throws Exception {
        return target.execInContainer("bash", "-c",
                "PGHOST=localhost PGDATABASE=e9x E9X_RESTORE_USER=" + user + " PGPASSWORD=" + (user.equals("e9x") ? "superuser-restore-test" : OWNER_PW)
                        + " /usr/local/bin/restore.sh /tmp/dump.sql.gz");
    }

    @Test
    void aBackupRestoredAsTheOwnerIntoAFreshVolumeMigratesAndRuns() throws Exception {
        Path dump = backedUpDatabase();
        Path pending = tmp.resolve("pending");
        Files.createDirectories(pending);
        Files.writeString(pending.resolve("V999999999999__after_restore.sql"), "CREATE TABLE after_restore (id int); DROP TABLE after_restore;"
                + "CREATE OR REPLACE FUNCTION auth_credentials_version(p_user_id uuid) RETURNS bigint LANGUAGE sql STABLE SECURITY DEFINER SET search_path = pg_catalog, public, pg_temp AS "
                + "$$ SELECT credentials_version FROM users WHERE id = p_user_id $$;");
        try (PostgreSQLContainer<?> target = freshVolume()) {
            target.copyFileToContainer(MountableFile.forHostPath(dump), "/tmp/dump.sql.gz");

            Container.ExecResult asSuperuser = restore(target, "e9x");
            assertEquals(3, asSuperuser.getExitCode(), asSuperuser.getStdout() + asSuperuser.getStderr());
            assertTrue(asSuperuser.getStderr().contains("refusing"), asSuperuser.getStderr());

            Container.ExecResult restored = restore(target, "e9x_owner");
            assertEquals(0, restored.getExitCode(), restored.getStdout() + restored.getStderr());
            System.out.println("restore output: " + restored.getStdout().strip());
            Container.ExecResult again = restore(target, "e9x_owner");
            assertEquals(4, again.getExitCode(), "it never restores over a database in use: " + again.getStderr());

            JdbcTemplate su = superuser(target);
            assertEquals("e9x_owner", su.queryForObject("select pg_get_userbyid(relowner) from pg_class where oid = 'public.users'::regclass", String.class));
            assertEquals("e9x_owner", su.queryForObject("select pg_get_userbyid(relowner) from pg_class where oid = 'public.flyway_schema_history'::regclass", String.class));
            assertEquals(0, su.queryForObject("""
                    select count(*) from pg_proc p join pg_namespace n on n.oid = p.pronamespace
                     where n.nspname = 'public' and p.prosecdef and pg_get_userbyid(p.proowner) <> 'e9x_owner'""", Integer.class), "no definer runs as anybody else");
            assertTrue(su.queryForObject("select count(*) from pg_policies where schemaname = 'public'", Integer.class) > 50, "the policies came back");
            assertEquals("A", su.queryForObject("select tgenabled::text from pg_trigger where tgrelid = 'audit_log'::regclass and not tgisinternal limit 1", String.class),
                    "the audit log's append-only trigger is ENABLE ALWAYS");

            flyway(target).validate();

            // the whole application on the restored volume: its Flyway, role guards and startup checks
            try (ConfigurableApplicationContext app = new SpringApplicationBuilder(E9xApplication.class).run(
                    "--server.port=0", "--spring.main.banner-mode=off",
                    "--spring.datasource.url=" + target.getJdbcUrl(), "--spring.datasource.username=e9x_app_login", "--spring.datasource.password=" + APP_PW,
                    "--spring.flyway.url=" + target.getJdbcUrl(), "--spring.flyway.user=e9x_owner", "--spring.flyway.password=" + OWNER_PW,
                    "--e9x.jobs.worker-datasource.url=" + target.getJdbcUrl(), "--e9x.jobs.worker-datasource.username=e9x_worker_login", "--e9x.jobs.worker-datasource.password=" + WORKER_PW,
                    "--e9x.grading.datasource.url=" + target.getJdbcUrl(), "--e9x.grading.datasource.username=e9x_grader_login", "--e9x.grading.datasource.password=" + GRADER_PW)) {
                assertTrue(app.isRunning());
                JdbcTemplate login = new JdbcTemplate(new DriverManagerDataSource(target.getJdbcUrl(), "e9x_app_login", APP_PW));
                assertEquals(0, login.queryForObject("select count(*) from users", Integer.class), "row level security holds for the app's login: no context, no row");
                assertEquals(1, su.queryForObject("select count(*) from users where id = ?", Integer.class, USER), "the data is there");
                assertEquals(1L, login.queryForObject("select auth_credentials_version(?)", Long.class, USER));
            }
            assertThrows(Exception.class, () -> su.update("delete from audit_log"), "the log is still append-only for everybody");
            // and the next release: its migration, applied by the owner on the restored database, replaces a definer too
            assertEquals(1, flyway(target, "filesystem:" + pending).migrate().migrationsExecuted);
        }
    }

    /** The other way, measured: what restore.sh refuses and why the README says not to. The dump goes in cleanly, and the database is then unusable. */
    @Test
    void aRestoreAsTheSuperuserCannotMigrate() throws Exception {
        Path dump = backedUpDatabase();
        Path pending = tmp.resolve("pending-su");
        Files.createDirectories(pending);
        Files.writeString(pending.resolve("V999999999999__after_restore.sql"), "CREATE TABLE after_restore (id int);");
        try (PostgreSQLContainer<?> target = freshVolume()) {
            target.copyFileToContainer(MountableFile.forHostPath(dump), "/tmp/dump.sql.gz");
            Container.ExecResult plain = target.execInContainer("bash", "-c",
                    "gunzip -c /tmp/dump.sql.gz | PGPASSWORD=superuser-restore-test psql -X -q -v ON_ERROR_STOP=1 -h localhost -U e9x -d e9x");
            assertEquals(0, plain.getExitCode(), plain.getStderr());
            assertNotEquals("e9x_owner", superuser(target).queryForObject("select pg_get_userbyid(relowner) from pg_class where oid = 'public.users'::regclass", String.class));
            FlywayException refused = assertThrows(FlywayException.class, () -> flyway(target, "filesystem:" + pending).migrate());
            System.out.println("restored as the superuser, Flyway as e9x_owner says: " + refused.getMessage().lines().findFirst().orElse(""));
        }
    }
}
