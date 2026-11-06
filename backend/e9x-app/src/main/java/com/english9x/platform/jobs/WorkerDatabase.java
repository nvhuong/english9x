package com.english9x.platform.jobs;

import com.english9x.platform.jobs.api.WorkerJdbc;
import com.zaxxer.hikari.HikariConfig;
import com.zaxxer.hikari.HikariDataSource;
import org.springframework.beans.factory.DisposableBean;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * The worker login's own small connection pool. Deliberately not a {@code javax.sql.DataSource} bean: nothing can
 * inject it by type, Spring Boot's primary data source and the RLS-bound transaction manager are untouched, and it is
 * reachable only through {@link JobStore} and, for handlers, {@link WorkerJdbc} (refused outside a running job).
 */
final class WorkerDatabase implements WorkerJdbc, DisposableBean {

    private static final ThreadLocal<Boolean> RUNNING_JOB = new ThreadLocal<>();

    private final HikariDataSource dataSource;
    private final JdbcTemplate jdbc;
    private final TransactionTemplate transaction;

    WorkerDatabase(WorkerDataSourceProperties p) {
        if (isBlank(p.url()) || isBlank(p.username()) || isBlank(p.password())) {
            throw new IllegalStateException("e9x.jobs.worker-datasource.url / username / password are required to run background jobs "
                    + "(set app.worker.enabled=false for an instance that must not run them)");
        }
        HikariConfig config = new HikariConfig();
        config.setPoolName("e9x-worker");
        config.setJdbcUrl(p.url());
        config.setUsername(p.username());
        config.setPassword(p.password());
        config.setMaximumPoolSize(p.maximumPoolSize());
        config.setMinimumIdle(0);
        config.setAutoCommit(true);
        this.dataSource = new HikariDataSource(config); // connects now: a wrong password stops startup
        try {
            WorkerRoleGuard.verify(dataSource);
        } catch (RuntimeException e) {
            dataSource.close();
            throw e;
        }
        this.jdbc = new JdbcTemplate(dataSource);
        this.transaction = new TransactionTemplate(new DataSourceTransactionManager(dataSource));
    }

    /** For {@link JobStore} and tests; handlers use {@link #jdbc()}. */
    JdbcTemplate raw() {
        return jdbc;
    }

    @Override
    public JdbcTemplate jdbc() {
        requireJob();
        return jdbc;
    }

    @Override
    public TransactionTemplate transaction() {
        requireJob();
        return transaction;
    }

    /** Marks the current thread as running a job until the returned scope is closed. */
    JobScope enterJob() {
        RUNNING_JOB.set(Boolean.TRUE);
        return RUNNING_JOB::remove;
    }

    /** Closing never throws. */
    interface JobScope extends AutoCloseable {
        @Override
        void close();
    }

    private static void requireJob() {
        if (RUNNING_JOB.get() == null) {
            throw new IllegalStateException("WorkerJdbc is available only to a job handler while it runs a job");
        }
    }

    private static boolean isBlank(String s) {
        return s == null || s.isBlank();
    }

    @Override
    public void destroy() {
        dataSource.close();
    }
}
