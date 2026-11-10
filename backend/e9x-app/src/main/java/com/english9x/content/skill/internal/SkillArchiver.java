package com.english9x.content.skill.internal;

import com.english9x.content.skill.api.GradingSkillVersions;
import com.english9x.workflow.api.events.ContentPublished;
import org.springframework.context.event.EventListener;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

/** A new PUBLISHED skill version replaces the older ones: archived in the approval's own transaction, as the REVIEWER who approved (see ScoreTableArchiver). */
@Component
class SkillArchiver {

    private final JdbcTemplate jdbc;

    SkillArchiver(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    @EventListener
    void published(ContentPublished event) {
        if (!GradingSkillVersions.REVIEW_TYPE.equals(event.type())) {
            return;
        }
        jdbc.update("""
                update grading_skill_versions set status = 'ARCHIVED', updated_at = now()
                 where status = 'PUBLISHED' and id <> ?
                   and grading_skill_id = (select grading_skill_id from grading_skill_versions where id = ?)
                """, event.versionId(), event.versionId());
    }
}
