package com.english9x.org.sweep;

import java.util.UUID;
import javax.sql.DataSource;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Profile;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;

/**
 * Test-only endpoints for the leak sweep. They live in test sources (never in the production jar) and are only
 * registered under the profiles below, so no other test context and no production OpenAPI ever sees them.
 *
 * <p>The sweep calls them through the REAL request path: a signed access token and the {@code X-Org-Context} header, bound by
 * {@code CurrentContextFilter} like any production request (since P1.5 there is no test filter standing in for it).
 *
 * <p>An endpoint with a parent and a child in its path must check BOTH against the caller's context: authorising the
 * parent and trusting the child id is the classic hole. The typed fixtures of {@code RlsLeakSweepIT} therefore try the
 * parent of the caller's own organisation with a child of another one, as well as the reverse and both foreign.
 */
public final class SweepEndpoints {

    private SweepEndpoints() {}

    /** A correct endpoint: reads an RLS-protected table through the application connection. */
    @RestController
    @Profile("rls-sweep")
    static class ProtectedController {
        private final JdbcTemplate jdbc;

        ProtectedController(JdbcTemplate jdbc) {
            this.jdbc = jdbc;
        }

        @GetMapping("/api/sweep/classes/{id}")
        @Transactional(readOnly = true)
        String get(@PathVariable UUID id) {
            return jdbc.queryForList("select name from classes where id = ?", String.class, id).stream()
                    .findFirst()
                    .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND));
        }
    }

    /**
     * Two path parameters, parent and child. The class is read through RLS (a foreign class is 404), and the member must
     * belong to the organisation that owns that class, also read through RLS (a foreign user under an own class is 404).
     */
    @RestController
    @Profile("rls-sweep")
    static class MemberController {
        private final JdbcTemplate jdbc;

        MemberController(JdbcTemplate jdbc) {
            this.jdbc = jdbc;
        }

        @GetMapping("/api/sweep/classes/{classId}/members/{userId}")
        @Transactional(readOnly = true)
        String get(@PathVariable UUID classId, @PathVariable UUID userId) {
            jdbc.queryForList("select name from classes where id = ?", String.class, classId).stream()
                    .findFirst()
                    .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND));
            boolean belongs = Boolean.TRUE.equals(jdbc.queryForObject(
                    "select exists (select 1 from org_memberships m join classes c on c.owner_org_id = m.org_id where c.id = ? and m.user_id = ?)",
                    Boolean.class, classId, userId));
            if (!belongs) {
                throw new ResponseStatusException(HttpStatus.NOT_FOUND);
            }
            return userId.toString();
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

        @GetMapping("/api/sweep-leaky/classes/{id}")
        String get(@PathVariable UUID id) {
            return privileged.queryForList("select name from classes where id = ?", String.class, id).stream()
                    .findFirst()
                    .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND));
        }
    }
}
