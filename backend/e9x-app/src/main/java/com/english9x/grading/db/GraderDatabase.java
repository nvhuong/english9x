package com.english9x.grading.db;

import com.zaxxer.hikari.HikariConfig;
import com.zaxxer.hikari.HikariDataSource;
import java.util.UUID;
import java.util.function.Function;
import org.springframework.beans.factory.DisposableBean;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * The grading login's own small connection pool: the answer keys are read, and scores are written, as {@code e9x_grader_login}, never as the request
 * login. Deliberately not a {@code javax.sql.DataSource} bean: nothing can inject it by type, Spring Boot's primary data source and the RLS-bound
 * transaction manager are untouched, and no controller can reach it (it is a module-internal class).
 *
 * <p>Every unit of work runs in its own transaction with the session context of ONE attempt ({@code app.user_id}, {@code app.org_id}, set with
 * {@code set_config(..., true)}: transaction-local, so nothing leaks to the next unit on a pooled connection). The grader's policies need both to
 * show a row, so the ids a caller passes (they come from an event or a job payload, which are hints) are proved by the row or find nothing.
 *
 * <p>For P4.2 (AI grading) and P4.3 (teacher grading): this is the pool to read a Writing/Speaking answer, a published grading skill and to write
 * {@code scores} rows; add the grants in your migration, never widen {@code V202611130004__scores.sql}.
 */
public final class GraderDatabase implements DisposableBean {

    private final HikariDataSource dataSource;
    private final TransactionTemplate readOnly;
    private final TransactionTemplate readWrite;
    private final JdbcTemplate jdbc;

    GraderDatabase(GraderDataSourceProperties p) {
        if (isBlank(p.url()) || isBlank(p.username()) || isBlank(p.password())) {
            throw new IllegalStateException("e9x.grading.datasource.url / username / password are required for automatic scoring "
                    + "(E9X_GRADER_DB_PASSWORD has no default)");
        }
        HikariConfig config = new HikariConfig();
        config.setPoolName("e9x-grader");
        config.setJdbcUrl(p.url());
        config.setUsername(p.username());
        config.setPassword(p.password());
        config.setMaximumPoolSize(p.maximumPoolSize());
        config.setMinimumIdle(0);
        config.setAutoCommit(true);
        this.dataSource = new HikariDataSource(config); // connects now: a wrong password stops startup
        try {
            GraderRoleGuard.verify(dataSource);
        } catch (RuntimeException e) {
            dataSource.close();
            throw e;
        }
        DataSourceTransactionManager manager = new DataSourceTransactionManager(dataSource);
        this.readWrite = new TransactionTemplate(manager);
        this.readOnly = new TransactionTemplate(manager);
        this.readOnly.setReadOnly(true);
        this.jdbc = new JdbcTemplate(dataSource);
    }

    /** A read-only transaction in the context of that person and organisation ({@code orgId} null = the personal / English9x context). */
    public <T> T read(UUID userId, UUID orgId, Function<JdbcTemplate, T> work) {
        return run(readOnly, userId, orgId, work);
    }

    /** A read-write transaction in the context of that person and organisation. It commits when {@code work} returns and rolls back when it throws. */
    public <T> T write(UUID userId, UUID orgId, Function<JdbcTemplate, T> work) {
        return run(readWrite, userId, orgId, work);
    }

    private <T> T run(TransactionTemplate tx, UUID userId, UUID orgId, Function<JdbcTemplate, T> work) {
        if (userId == null) {
            throw new IllegalArgumentException("userId is required");
        }
        return tx.execute(status -> {
            jdbc.queryForList("select set_config('app.user_id', ?, true), set_config('app.org_id', ?, true), "
                    + "set_config('app.e9x_staff', '', true), set_config('app.org_admin', '', true)",
                    userId.toString(), orgId == null ? "" : orgId.toString());
            return work.apply(jdbc);
        });
    }

    private static boolean isBlank(String s) {
        return s == null || s.isBlank();
    }

    @Override
    public void destroy() {
        dataSource.close();
    }
}
