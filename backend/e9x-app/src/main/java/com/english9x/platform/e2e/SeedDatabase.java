package com.english9x.platform.e2e;

import com.zaxxer.hikari.HikariConfig;
import com.zaxxer.hikari.HikariDataSource;
import org.springframework.beans.factory.DisposableBean;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Profile;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

/**
 * How the E2E seed API writes: through its own small pool as the TABLE OWNER (e9x_owner, the login Flyway uses; it
 * is NOSUPERUSER NOBYPASSRLS and the policies are neither changed nor FORCEd for it). A fixture such as "a centre
 * with 2 teachers and 5 learners" spans several organisations at once, which the runtime login cannot do by design.
 *
 * <p>Only exists under the Spring profile {@code e2e}. Like the worker pool it is deliberately not a
 * {@code javax.sql.DataSource} bean: nothing can inject it by type and Spring Boot's primary data source and the
 * RLS-bound transaction manager are untouched. No new secret: it reuses the Flyway credentials the instance already has.
 */
@Component
@Profile("e2e")
final class SeedDatabase implements DisposableBean {

    private final HikariDataSource dataSource;
    private final JdbcTemplate jdbc;

    SeedDatabase(
            @Value("${spring.datasource.url}") String url,
            @Value("${spring.flyway.user}") String user,
            @Value("${spring.flyway.password}") String password) {
        HikariConfig config = new HikariConfig();
        config.setPoolName("e9x-seed");
        config.setJdbcUrl(url);
        config.setUsername(user);
        config.setPassword(password);
        config.setMaximumPoolSize(2);
        config.setMinimumIdle(0);
        config.setAutoCommit(true);
        this.dataSource = new HikariDataSource(config);
        this.jdbc = new JdbcTemplate(dataSource);
    }

    JdbcTemplate jdbc() {
        return jdbc;
    }

    @Override
    public void destroy() {
        dataSource.close();
    }
}
