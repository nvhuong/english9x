package com.english9x.content.test.internal;

import com.english9x.content.test.api.TestVersions;
import com.english9x.workflow.api.events.ContentPublished;
import org.springframework.context.event.EventListener;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

/**
 * A new PUBLISHED version of a test replaces the older ones: they are archived in the approval's own transaction, as the REVIEWER who approved. An attempt
 * that began on an archived version keeps reading it ({@code TestVersions#forExamRoom} serves PUBLISHED and ARCHIVED); nobody can begin a new attempt on it.
 * The overrides (score tables, grading policies of TEST scope) are keyed by the test, so they stay.
 */
@Component
class TestArchiver {

    private final JdbcTemplate jdbc;

    TestArchiver(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    @EventListener
    void published(ContentPublished event) {
        if (!TestVersions.REVIEW_TYPE.equals(event.type())) {
            return;
        }
        jdbc.update("""
                update test_versions set status = 'ARCHIVED', updated_at = now()
                 where status = 'PUBLISHED' and id <> ?
                   and test_id = (select test_id from test_versions where id = ?)
                """, event.versionId(), event.versionId());
    }
}
