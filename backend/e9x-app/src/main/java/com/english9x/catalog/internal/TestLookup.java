package com.english9x.catalog.internal;

import java.util.Optional;
import java.util.UUID;
import org.springframework.stereotype.Component;

/**
 * From a test version to its test. Overrides (score tables, grading policies of TEST scope) are keyed by the test, not by the version,
 * so a new published version of the same test keeps its override; {@code resolve} gets a version id and asks this class for the test.
 *
 * <p>PLUG-IN POINT FOR P2.6. {@code tests} and {@code test_versions} do not exist yet and TEST-scope rows are refused by the database
 * (CHECK {@code *_test_scope_refused_ck}), so no test has an override and the answer is always empty. P2.6, which lifts that CHECK,
 * replaces the body with {@code select test_id from test_versions where id = ?} (run under the caller's RLS context, like every other
 * statement of this module) and adds a test where publishing version 2 of a test keeps the override set while it had version 1.
 */
@Component
class TestLookup {

    Optional<UUID> testOf(UUID testVersionId) {
        return Optional.empty();
    }
}
