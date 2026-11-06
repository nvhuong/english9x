package com.english9x.support;

import java.nio.file.Path;
import java.sql.Connection;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.UUID;
import javax.sql.DataSource;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.utility.MountableFile;

/**
 * One PostgreSQL 16 for the whole test JVM, with the production role split. The container runs the same
 * infra/postgres/init/01-roles.sh the Compose stack runs, so the roles are created the production way:
 * e9x_owner (Flyway, owns the schema) and e9x_app_login (the app; member of e9x_app, no BYPASSRLS).
 * Every test class gets its own database ({@link #createDatabase()}) owned by e9x_owner.
 * The passwords are throw-away test values.
 */
public final class TestPostgres {

    public static final String OWNER = "e9x_owner";
    public static final String OWNER_PASSWORD = "owner-test-only";
    public static final String APP_LOGIN = "e9x_app_login";
    public static final String APP_PASSWORD = "app-test-only";
    public static final String WORKER_LOGIN = "e9x_worker_login";
    public static final String WORKER_PASSWORD = "worker-test-only";

    private static final PostgreSQLContainer<?> CONTAINER = start();

    private TestPostgres() {}

    private static PostgreSQLContainer<?> start() {
        Path script = Path.of("..", "..", "infra", "postgres", "init", "01-roles.sh").toAbsolutePath().normalize();
        PostgreSQLContainer<?> c = new PostgreSQLContainer<>("postgres:16")
                .withDatabaseName("bootstrap")
                .withEnv("E9X_OWNER_PASSWORD", OWNER_PASSWORD)
                .withEnv("E9X_APP_PASSWORD", APP_PASSWORD)
                .withEnv("E9X_WORKER_PASSWORD", WORKER_PASSWORD)
                // Spring caches many application contexts (each holds a pool) for the whole test JVM
                .withCommand("postgres", "-c", "max_connections=500")
                .withCopyFileToContainer(
                        MountableFile.forHostPath(script, 0755), "/docker-entrypoint-initdb.d/01-roles.sh");
        c.start(); // stopped by Testcontainers' Ryuk when the JVM exits
        return c;
    }

    public static PostgreSQLContainer<?> container() {
        return CONTAINER;
    }

    public static String jdbcUrl(String database) {
        return "jdbc:postgresql://" + CONTAINER.getHost() + ":" + CONTAINER.getMappedPort(5432) + "/" + database;
    }

    /** A fresh empty database owned by e9x_owner (so Flyway as e9x_owner can migrate it). */
    public static String createDatabase() {
        String name = "t_" + UUID.randomUUID().toString().replace("-", "");
        createDatabase(name, null);
        return name;
    }

    public static void createDatabase(String name, String template) {
        try (Connection c = superuser("bootstrap").getConnection(); Statement s = c.createStatement()) {
            s.execute("CREATE DATABASE \"" + name + "\" OWNER " + OWNER
                    + (template == null ? "" : " TEMPLATE \"" + template + "\""));
        } catch (SQLException e) {
            throw new IllegalStateException(e);
        }
    }

    /** The container superuser, for fixtures and assertions that must see past RLS. Never used by the app. */
    public static DataSource superuser(String database) {
        return new DriverManagerDataSource(jdbcUrl(database), CONTAINER.getUsername(), CONTAINER.getPassword());
    }

    public static DataSource owner(String database) {
        return new DriverManagerDataSource(jdbcUrl(database), OWNER, OWNER_PASSWORD);
    }

    public static DataSource worker(String database) {
        return new DriverManagerDataSource(jdbcUrl(database), WORKER_LOGIN, WORKER_PASSWORD);
    }

    public static DataSource app(String database) {
        return new DriverManagerDataSource(jdbcUrl(database), APP_LOGIN, APP_PASSWORD);
    }

    /**
     * Points the application at the database: runtime = e9x_app_login, Flyway = e9x_owner, background jobs =
     * e9x_worker_login.
     */
    public static void connect(DynamicPropertyRegistry registry, String database) {
        registry.add("spring.datasource.url", () -> jdbcUrl(database));
        registry.add("spring.datasource.username", () -> APP_LOGIN);
        registry.add("spring.datasource.password", () -> APP_PASSWORD);
        registry.add("spring.flyway.user", () -> OWNER);
        registry.add("spring.flyway.password", () -> OWNER_PASSWORD);
        registry.add("e9x.jobs.worker-datasource.url", () -> jdbcUrl(database));
        registry.add("e9x.jobs.worker-datasource.username", () -> WORKER_LOGIN);
        registry.add("e9x.jobs.worker-datasource.password", () -> WORKER_PASSWORD);
    }
}
