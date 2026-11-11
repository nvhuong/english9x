package com.english9x.course.internal;

import com.english9x.workflow.api.ContentStatus;
import java.math.BigDecimal;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

/** {@code courses}, {@code course_versions}, chapters and lessons, in the caller's transaction: Row-Level Security answers for the bound user and organisation. */
@Repository
class CourseRepository {

    record Header(UUID id, UUID ownerOrgId, String examCode, String code, UUID createdBy) {}

    record LessonRow(String kind, UUID refId, String title, Integer maxAttempts) {}

    record ChapterRow(String title, double passThreshold, List<LessonRow> lessons) {}

    /** A lesson as stored; {@code id} is the id the gate and the progress use. */
    record Lesson(UUID id, String kind, UUID refId, String title, Integer maxAttempts) {}

    record Chapter(UUID id, int position, String title, double passThreshold, List<Lesson> lessons) {}

    record Version(UUID versionId, UUID courseId, String code, int versionNo, ContentStatus status, UUID ownerOrgId, String examCode, String title,
                   String description, String authorDisplay, String unlockRule, List<Chapter> chapters, String sharedStatus, UUID createdBy,
                   Instant createdAt, Instant updatedAt) {}

    record Summary(UUID courseId, UUID versionId, String code, int versionNo, ContentStatus status, String examCode, String title, String sharedStatus,
                   int chapters, int lessons, UUID createdBy, Instant updatedAt) {}

    private static final String SELECT = "select v.id, v.course_id, c.code, v.version_no, v.status, v.owner_org_id, v.exam_code, v.title, v.description, "
            + "v.author_display, v.unlock_rule, c.shared_status, v.created_by, v.created_at, v.updated_at from course_versions v join courses c on c.id = v.course_id ";

    private final JdbcTemplate jdbc;

    CourseRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    private static Instant instant(Timestamp t) {
        return t == null ? null : t.toInstant();
    }

    private Version version(ResultSet rs) throws SQLException {
        UUID id = rs.getObject("id", UUID.class);
        return new Version(id, rs.getObject("course_id", UUID.class), rs.getString("code"), rs.getInt("version_no"), ContentStatus.valueOf(rs.getString("status")),
                rs.getObject("owner_org_id", UUID.class), rs.getString("exam_code"), rs.getString("title"), rs.getString("description"),
                rs.getString("author_display"), rs.getString("unlock_rule"), chapters(id), rs.getString("shared_status"), rs.getObject("created_by", UUID.class),
                instant(rs.getTimestamp("created_at")), instant(rs.getTimestamp("updated_at")));
    }

    List<Chapter> chapters(UUID versionId) {
        record Row(UUID chapterId, int position, String chapterTitle, double threshold, UUID lessonId, String kind, UUID refId, String title, Integer max) {}
        List<Row> rows = jdbc.query("""
                select c.id, c.position, c.title, c.pass_threshold, l.id, l.kind, l.ref_id, l.title, l.max_attempts
                  from course_chapters c left join course_lessons l on l.course_version_id = c.course_version_id and l.chapter_position = c.position
                 where c.course_version_id = ? order by c.position, l.position
                """, (rs, n) -> new Row(rs.getObject(1, UUID.class), rs.getInt(2), rs.getString(3), rs.getBigDecimal(4).doubleValue(), rs.getObject(5, UUID.class),
                rs.getString(6), rs.getObject(7, UUID.class), rs.getString(8), rs.getObject(9) == null ? null : rs.getInt(9)), versionId);
        List<Chapter> out = new ArrayList<>();
        for (Row r : rows) {
            if (out.isEmpty() || out.get(out.size() - 1).position() != r.position()) {
                out.add(new Chapter(r.chapterId(), r.position(), r.chapterTitle(), r.threshold(), new ArrayList<>()));
            }
            if (r.lessonId() != null) {
                out.get(out.size() - 1).lessons().add(new Lesson(r.lessonId(), r.kind(), r.refId(), r.title(), r.max()));
            }
        }
        return out;
    }

    Optional<Header> header(UUID courseId) {
        return jdbc.query("select id, owner_org_id, exam_code, code, created_by from courses where id = ?",
                (rs, n) -> new Header(rs.getObject(1, UUID.class), rs.getObject(2, UUID.class), rs.getString(3), rs.getString(4), rs.getObject(5, UUID.class)), courseId)
                .stream().findFirst();
    }

    Optional<Version> latest(UUID courseId) {
        return jdbc.query(SELECT + "where v.course_id = ? order by v.version_no desc limit 1", (rs, n) -> version(rs), courseId).stream().findFirst();
    }

    Optional<Version> versionNo(UUID courseId, int versionNo) {
        return jdbc.query(SELECT + "where v.course_id = ? and v.version_no = ?", (rs, n) -> version(rs), courseId, versionNo).stream().findFirst();
    }

    List<Version> versions(UUID courseId) {
        return jdbc.query(SELECT + "where v.course_id = ? order by v.version_no", (rs, n) -> version(rs), courseId);
    }

    Optional<Version> find(UUID versionId) {
        return jdbc.query(SELECT + "where v.id = ?", (rs, n) -> version(rs), versionId).stream().findFirst();
    }

    List<Summary> list(String examCode, ContentStatus status, int limit, int offset) {
        return jdbc.query("select * from (select distinct on (v.course_id) v.id, v.course_id, c.code, v.version_no, v.status, v.exam_code, v.title, c.shared_status, "
                + "(select count(*) from course_chapters h where h.course_version_id = v.id) as chapters, "
                + "(select count(*) from course_lessons l where l.course_version_id = v.id) as lessons, v.created_by, v.updated_at "
                + "from course_versions v join courses c on c.id = v.course_id " + (examCode == null ? "" : "where v.exam_code = ? ")
                + "order by v.course_id, v.version_no desc) latest " + (status == null ? "" : "where status = '" + status.name() + "' ")
                + "order by updated_at desc, id limit ? offset ?",
                (rs, n) -> new Summary(rs.getObject("course_id", UUID.class), rs.getObject("id", UUID.class), rs.getString("code"), rs.getInt("version_no"),
                        ContentStatus.valueOf(rs.getString("status")), rs.getString("exam_code"), rs.getString("title"), rs.getString("shared_status"),
                        rs.getInt("chapters"), rs.getInt("lessons"), rs.getObject("created_by", UUID.class), instant(rs.getTimestamp("updated_at"))),
                examCode == null ? new Object[] {limit, offset} : new Object[] {examCode, limit, offset});
    }

    // ---- writes ---------------------------------------------------------------------------------------------------------------------

    void insertHeader(Header h, String title) {
        jdbc.update("insert into courses(id, owner_org_id, exam_code, code, title, status, created_by) values (?,?,?,?,?,'DRAFT',?)",
                h.id(), h.ownerOrgId(), h.examCode(), h.code(), title, h.createdBy());
    }

    void insertVersion(UUID id, Header h, int versionNo, String title, String description, String authorDisplay, String unlockRule, List<ChapterRow> chapters,
                       UUID by) {
        jdbc.update("insert into course_versions(id, course_id, owner_org_id, exam_code, version_no, title, description, author_display, unlock_rule, created_by) "
                + "values (?,?,?,?,?,?,?,?,?,?)", id, h.id(), h.ownerOrgId(), h.examCode(), versionNo, title, description, authorDisplay, unlockRule, by);
        insertChapters(id, h, chapters);
    }

    private void insertChapters(UUID versionId, Header h, List<ChapterRow> chapters) {
        for (int i = 0; i < chapters.size(); i++) {
            ChapterRow c = chapters.get(i);
            jdbc.update("insert into course_chapters(course_version_id, position, title, pass_threshold, owner_org_id, exam_code) values (?,?,?,?,?,?)",
                    versionId, i + 1, c.title(), BigDecimal.valueOf(c.passThreshold()), h.ownerOrgId(), h.examCode());
            for (int j = 0; j < c.lessons().size(); j++) {
                LessonRow l = c.lessons().get(j);
                jdbc.update("insert into course_lessons(course_version_id, chapter_position, position, kind, ref_id, title, max_attempts, owner_org_id, exam_code) "
                        + "values (?,?,?,?,?,?,?,?,?)", versionId, i + 1, j + 1, l.kind(), l.refId(), l.title(), l.maxAttempts(), h.ownerOrgId(), h.examCode());
            }
        }
    }

    /** Rewrites an editable version: 0 when it is not (or no longer) a DRAFT / CHANGES_REQUESTED. The chapters are replaced (the child guards allow it only then). */
    int updateDraft(UUID versionId, Header h, String title, String description, String authorDisplay, String unlockRule, List<ChapterRow> chapters) {
        int rows = jdbc.update("update course_versions set title = ?, description = ?, author_display = ?, unlock_rule = ?, updated_at = now() "
                + "where id = ? and status in ('DRAFT', 'CHANGES_REQUESTED')", title, description, authorDisplay, unlockRule, versionId);
        if (rows == 1) {
            jdbc.update("delete from course_lessons where course_version_id = ?", versionId);
            jdbc.update("delete from course_chapters where course_version_id = ?", versionId);
            insertChapters(versionId, h, chapters);
        }
        return rows;
    }
}
