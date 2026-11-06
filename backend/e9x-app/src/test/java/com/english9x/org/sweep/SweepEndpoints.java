package com.english9x.org.sweep;

import com.english9x.org.api.CurrentContextHolder;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.util.UUID;
import javax.sql.DataSource;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Profile;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.filter.OncePerRequestFilter;
import org.springframework.web.server.ResponseStatusException;

/**
 * Test-only endpoints for the leak sweep. They live in test sources (never in the production jar) and are only
 * registered under the profiles below, so no other test context and no production OpenAPI ever sees them.
 */
public final class SweepEndpoints {

    private SweepEndpoints() {}

    /** Stand-in for P1.5's request filter: binds the acting user / org from test headers. */
    @Configuration
    @Profile("rls-sweep")
    static class TestContextConfig {
        @Bean
        OncePerRequestFilter testContextFilter() {
            return new OncePerRequestFilter() {
                @Override
                protected void doFilterInternal(HttpServletRequest req, HttpServletResponse res, FilterChain chain)
                        throws ServletException, IOException {
                    String user = req.getHeader("X-Test-User");
                    String org = req.getHeader("X-Test-Org");
                    if (user == null) {
                        chain.doFilter(req, res);
                        return;
                    }
                    try (var scope = CurrentContextHolder.bind(UUID.fromString(user), org == null ? null : UUID.fromString(org))) {
                        chain.doFilter(req, res);
                    }
                }
            };
        }
    }

    /** A correct endpoint: reads an RLS-protected table through the application connection. */
    @RestController
    @Profile("rls-sweep")
    static class ProtectedController {
        private final JdbcTemplate jdbc;

        ProtectedController(JdbcTemplate jdbc) {
            this.jdbc = jdbc;
        }

        @GetMapping("/sweep/classes/{id}")
        @Transactional(readOnly = true)
        String get(@PathVariable UUID id) {
            return jdbc.queryForList("select name from classes where id = ?", String.class, id).stream()
                    .findFirst()
                    .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND));
        }
    }

    /** Deliberately leaky: reads the same table over a superuser connection, i.e. past RLS. */
    @RestController
    @Profile("rls-sweep-leak")
    static class LeakyController {
        private final JdbcTemplate privileged;

        LeakyController(@Value("${e9x.test.leaky-url}") String url, @Value("${e9x.test.leaky-user}") String user,
                        @Value("${e9x.test.leaky-password}") String password) {
            DataSource ds = new DriverManagerDataSource(url, user, password);
            this.privileged = new JdbcTemplate(ds);
        }

        @GetMapping("/sweep-leaky/classes/{id}")
        String get(@PathVariable UUID id) {
            return privileged.queryForList("select name from classes where id = ?", String.class, id).stream()
                    .findFirst()
                    .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND));
        }
    }
}
