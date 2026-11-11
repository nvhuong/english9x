package com.english9x.course.internal;

import com.english9x.course.api.CourseVersions;
import com.english9x.workflow.api.events.ContentPublished;
import org.springframework.context.event.EventListener;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

/** A new PUBLISHED version of a course archives the older ones in the approval's own transaction. Learners keep reading an archived version they began on. */
@Component
class CourseArchiver {

    private final JdbcTemplate jdbc;

    CourseArchiver(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    @EventListener
    void published(ContentPublished event) {
        if (!CourseVersions.REVIEW_TYPE.equals(event.type())) {
            return;
        }
        jdbc.update("""
                update course_versions set status = 'ARCHIVED', updated_at = now()
                 where status = 'PUBLISHED' and id <> ?
                   and course_id = (select course_id from course_versions where id = ?)
                """, event.versionId(), event.versionId());
    }
}
