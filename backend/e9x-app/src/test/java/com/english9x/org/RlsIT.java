package com.english9x.org;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.english9x.support.TestPostgres;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.Statement;
import java.util.List;
import java.util.stream.Stream;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DynamicTest;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestFactory;
import org.testcontainers.containers.Container.ExecResult;
import org.testcontainers.utility.MountableFile;

/**
 * Re-runs every backend/db-tests/*.sql (the same scripts scripts/test-db.sh runs) against a schema migrated by
 * Flyway as e9x_owner, each script in its own copy of that schema, through real psql with ON_ERROR_STOP.
 */
class RlsIT {

    static final Path DB_TESTS = Path.of("..", "db-tests").toAbsolutePath().normalize();
    static final String TEMPLATE = "rls_template";

    @BeforeAll
    static void migrateTemplate() {
        TestPostgres.createDatabase(TEMPLATE, null);
        Flyway.configure()
                .dataSource(TestPostgres.jdbcUrl(TEMPLATE), TestPostgres.OWNER, TestPostgres.OWNER_PASSWORD)
                .locations("classpath:db/migration")
                .load()
                .migrate();
    }

    @Test
    void scriptsAreFound() throws IOException {
        assertTrue(scripts().size() >= 18, "expected the db-tests scripts, found " + scripts());
    }

    @Test
    void flywayRanAsTheOwnerAndTheOwnerOwnsEveryTable() throws Exception {
        try (Connection c = TestPostgres.superuser(TEMPLATE).getConnection();
                Statement s = c.createStatement()) {
            ResultSet rs = s.executeQuery("select count(*) from flyway_schema_history where installed_by <> 'e9x_owner'");
            rs.next();
            assertEquals(0, rs.getInt(1));
            rs = s.executeQuery("select count(*) from pg_tables where schemaname='public' and tableowner <> 'e9x_owner'");
            rs.next();
            assertEquals(0, rs.getInt(1));
        }
    }

    @TestFactory
    Stream<DynamicTest> dbScripts() throws IOException {
        return scripts().stream().map(script -> DynamicTest.dynamicTest(script.getFileName().toString(), () -> run(script)));
    }

    static List<Path> scripts() throws IOException {
        try (Stream<Path> files = Files.list(DB_TESTS)) {
            return files.filter(p -> p.getFileName().toString().matches("\\d+_.*\\.sql")).sorted().toList();
        }
    }

    static void run(Path script) throws Exception {
        String db = "rls_" + Math.abs(script.getFileName().toString().hashCode()) + "_" + System.nanoTime();
        TestPostgres.createDatabase(db, TEMPLATE);
        String inContainer = "/tmp/" + script.getFileName();
        TestPostgres.container().copyFileToContainer(MountableFile.forHostPath(script), inContainer);
        ExecResult r = TestPostgres.container()
                .execInContainer("psql", "-U", TestPostgres.container().getUsername(), "-d", db,
                        "-v", "ON_ERROR_STOP=1", "-q", "-X", "-f", inContainer);
        assertEquals(0, r.getExitCode(), script.getFileName() + " failed:\n" + r.getStdout() + "\n" + r.getStderr());
    }
}
