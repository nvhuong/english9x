package com.english9x.course.internal;

import com.english9x.billing.api.Entitlements;
import com.english9x.course.api.CourseOutline;
import com.english9x.course.api.CourseVersions;
import com.english9x.course.api.LessonAccess;
import com.english9x.course.api.LessonGate;
import com.english9x.course.api.LessonNotFoundException;
import com.english9x.course.api.ProgressSource;
import com.english9x.domain.learning.ChapterRef;
import com.english9x.domain.learning.LessonKind;
import com.english9x.domain.learning.LessonRef;
import com.english9x.domain.learning.LessonStatus;
import com.english9x.domain.learning.UnlockPolicy;
import com.english9x.domain.learning.UnlockRule;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

/** The reads other modules use: the outline of a version, the version in force, and the lesson gate. All in the caller's transaction and security context. */
@Component
class CourseReads implements CourseOutline, CourseVersions, LessonGate {

    private final JdbcTemplate jdbc;
    private final Entitlements entitlements;
    private final ObjectProvider<ProgressSource> progress;

    CourseReads(JdbcTemplate jdbc, Entitlements entitlements, ObjectProvider<ProgressSource> progress) {
        this.jdbc = jdbc;
        this.entitlements = entitlements;
        this.progress = progress;
    }

    @Override
    @Transactional(readOnly = true)
    public List<ChapterRef> lessonsInOrder(UUID courseVersionId) {
        record Row(UUID chapter, double threshold, UUID lesson, String kind) {}
        List<Row> rows = jdbc.query("""
                select c.id, c.pass_threshold, l.id, l.kind
                  from course_chapters c left join course_lessons l on l.course_version_id = c.course_version_id and l.chapter_position = c.position
                 where c.course_version_id = ? order by c.position, l.position
                """, (rs, n) -> new Row(rs.getObject(1, UUID.class), rs.getBigDecimal(2).doubleValue(), rs.getObject(3, UUID.class), rs.getString(4)), courseVersionId);
        List<ChapterRef> out = new ArrayList<>();
        UUID current = null;
        double threshold = 0;
        List<LessonRef> lessons = new ArrayList<>();
        for (Row r : rows) {
            if (!r.chapter().equals(current)) {
                if (current != null) {
                    out.add(new ChapterRef(current.toString(), lessons, threshold));
                }
                current = r.chapter();
                threshold = r.threshold();
                lessons = new ArrayList<>();
            }
            if (r.lesson() != null) {
                lessons.add(new LessonRef(r.lesson().toString(), LessonKind.valueOf(r.kind())));
            }
        }
        if (current != null) {
            out.add(new ChapterRef(current.toString(), lessons, threshold));
        }
        return out;
    }

    @Override
    @Transactional(readOnly = true)
    public Optional<UUID> publishedVersionOf(UUID courseId) {
        return jdbc.query("select id from course_versions where course_id = ? and status = 'PUBLISHED' order by version_no desc limit 1",
                (rs, n) -> rs.getObject(1, UUID.class), courseId).stream().findFirst();
    }

    @Override
    @Transactional(readOnly = true)
    public Optional<UUID> courseOfLesson(UUID lessonId) {
        return jdbc.query("select v.course_id from course_lessons l join course_versions v on v.id = l.course_version_id where l.id = ?",
                (rs, n) -> rs.getObject(1, UUID.class), lessonId).stream().findFirst();
    }

    @Override
    @Transactional(readOnly = true)
    public LessonAccess check(UUID userId, UUID lessonId, Instant now) {
        return check(userId, lessonId, now, null);
    }

    /** @param progressOverride the progress to judge by instead of asking the {@link ProgressSource} (E2E only: the learning module owns the real one) */
    @Transactional(readOnly = true)
    LessonAccess check(UUID userId, UUID lessonId, Instant now, Map<String, LessonStatus> progressOverride) {
        record Row(UUID version, UUID course, String rule) {}
        Row row = jdbc.query("select v.id, v.course_id, v.unlock_rule from course_lessons l join course_versions v on v.id = l.course_version_id where l.id = ?",
                (rs, n) -> new Row(rs.getObject(1, UUID.class), rs.getObject(2, UUID.class), rs.getString(3)), lessonId).stream().findFirst()
                .orElseThrow(() -> new LessonNotFoundException(lessonId));
        if (!entitlements.activeFor(userId, row.course(), now)) {
            return LessonAccess.NOT_ENTITLED;
        }
        UnlockRule rule = UnlockRule.valueOf(row.rule());
        if (rule == UnlockRule.FREE) {
            return LessonAccess.ALLOWED;
        }
        Map<String, LessonStatus> done = progressOverride != null ? progressOverride
                : progress.stream().findFirst().map(p -> p.progress(userId, row.version())).orElse(Map.of());
        return UnlockPolicy.isUnlocked(rule, lessonsInOrder(row.version()), done, lessonId.toString()) ? LessonAccess.ALLOWED : LessonAccess.LOCKED;
    }
}
