package com.english9x.catalog.internal;

import java.util.Optional;
import java.util.UUID;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

/**
 * From a test version to its test. Overrides (score tables, grading policies of TEST scope) are keyed by the test, not by the version, so a new published
 * version of the same test keeps its override; {@code resolve} gets a version id and asks this class for the test.
 *
 * <p>A plain {@code select test_id from test_versions where id = ?}, run under the caller's Row-Level Security like every other statement of this module:
 * a PUBLISHED or ARCHIVED version of English9x or of the current organisation is visible to everybody in that context (the learner, the grader), a draft
 * only to the staff of the exam. Read through SQL rather than {@code content.test.api}: {@code content} depends on {@code catalog}, not the other way round.
 */
@Component
class TestLookup {

    private final JdbcTemplate jdbc;

    TestLookup(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    Optional<UUID> testOf(UUID testVersionId) {
        return jdbc.query("select test_id from test_versions where id = ?", (rs, n) -> rs.getObject(1, UUID.class), testVersionId).stream().findFirst();
    }
}
