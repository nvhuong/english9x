package com.english9x.content.scoring.internal;

import com.english9x.content.scoring.api.ScoreTableVersions;
import com.english9x.workflow.api.events.ContentPublished;
import org.springframework.context.event.EventListener;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

/**
 * A new PUBLISHED version replaces the older ones: they are archived in the approval's own transaction, as the REVIEWER who approved (the policy lets a
 * reviewer archive a published version, and nobody else). {@code ScoreTables#resolve} takes the highest published number anyway; this keeps the history
 * honest ("which version is in force") without a second step for the reviewer.
 */
@Component
class ScoreTableArchiver {

    private final JdbcTemplate jdbc;

    ScoreTableArchiver(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    @EventListener
    void published(ContentPublished event) {
        if (!ScoreTableVersions.REVIEW_TYPE.equals(event.type())) {
            return;
        }
        jdbc.update("""
                update score_table_versions set status = 'ARCHIVED', updated_at = now()
                 where status = 'PUBLISHED' and id <> ?
                   and score_table_id = (select score_table_id from score_table_versions where id = ?)
                """, event.versionId(), event.versionId());
    }
}
