package com.english9x.learning.internal;

import com.english9x.course.api.ProgressSource;
import com.english9x.domain.learning.LessonStatus;
import java.util.HashMap;
import java.util.Map;
import java.util.UUID;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

/**
 * What the lesson gate of P3.1 reads. Progress is stored by the lesson's {@code ref_id} (the strategy lesson or test version it points at) and the course, so it
 * is told here under the ids of THIS course version's lessons: a new version of the course that keeps a lesson keeps the learner's progress on it.
 */
@Component
class ProgressSourceImpl implements ProgressSource {

    private final JdbcTemplate jdbc;

    ProgressSourceImpl(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    @Override
    @Transactional(readOnly = true)
    public Map<String, LessonStatus> progress(UUID userId, UUID courseVersionId) {
        Map<String, LessonStatus> out = new HashMap<>();
        jdbc.query("""
                select l.id, p.completed, p.best_score
                  from course_lessons l join course_versions v on v.id = l.course_version_id
                  join lesson_progress p on p.user_id = ? and p.course_id = v.course_id and p.ref_id = l.ref_id
                 where l.course_version_id = ?
                """, rs -> {
            out.put(rs.getObject(1, UUID.class).toString(), new LessonStatus(rs.getBoolean(2), rs.getBigDecimal(3) == null ? null : rs.getBigDecimal(3).doubleValue()));
        }, userId, courseVersionId);
        return out;
    }
}
