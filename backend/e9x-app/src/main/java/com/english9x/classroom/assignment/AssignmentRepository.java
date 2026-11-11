package com.english9x.classroom.assignment;

import com.english9x.classroom.assignment.AssignmentDtos.AssignmentView;
import com.english9x.classroom.assignment.AssignmentDtos.MyAssignment;
import com.english9x.classroom.assignment.AssignmentDtos.ReportRow;
import com.english9x.classroom.assignment.AssignmentDtos.Status;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

/** Statements of the assignment endpoints (caller's transaction, caller's Row-Level Security context). */
@Repository
class AssignmentRepository {

    private final JdbcTemplate jdbc;

    AssignmentRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    private static Timestamp ts(Instant i) {
        return Timestamp.from(i);
    }

    boolean targetOk(UUID lessonId, UUID testVersionId, UUID orgId) {
        return Boolean.TRUE.equals(jdbc.queryForObject("select classroom_target_ok(?, ?, ?)", Boolean.class, lessonId, testVersionId, orgId));
    }

    UUID insert(UUID classId, UUID orgId, UUID lessonId, UUID testVersionId, Instant dueAt, UUID by) {
        return jdbc.queryForObject("insert into assignments(class_id, org_id, lesson_id, test_version_id, due_at, created_by) values (?, ?, ?, ?, ?, ?) returning id",
                UUID.class, classId, orgId, lessonId, testVersionId, ts(dueAt), by);
    }

    List<UUID> memberIds(UUID classId) {
        return jdbc.queryForList("select user_id from class_members where class_id = ? order by user_id", UUID.class, classId);
    }

    /** The class of an assignment of this organisation, as far as the caller may see it. */
    Optional<UUID> classOf(UUID assignmentId, UUID orgId) {
        return jdbc.query("select class_id from assignments where id = ? and org_id = ?", (rs, i) -> rs.getObject(1, UUID.class), assignmentId, orgId).stream().findFirst();
    }

    List<AssignmentView> ofClass(UUID classId, Instant now) {
        return jdbc.query("select r_id, r_lesson_id, r_test_version_id, r_title, r_due_at, r_created_at, r_total, r_submitted, r_late, r_missing "
                + "from classroom_class_assignments(?, ?)", (rs, i) -> new AssignmentView(rs.getObject(1, UUID.class), rs.getObject(2, UUID.class), rs.getObject(3, UUID.class),
                rs.getString(4), rs.getTimestamp(5).toInstant(), rs.getTimestamp(6).toInstant(), rs.getInt(7), rs.getInt(8), rs.getInt(9), rs.getInt(10)), classId, ts(now));
    }

    List<ReportRow> report(UUID assignmentId, Instant now) {
        return jdbc.query("select r_user_id, r_display_name, r_email, r_status, r_submitted_at from classroom_assignment_report(?, ?)",
                (rs, i) -> new ReportRow(rs.getObject(1, UUID.class), rs.getString(2), rs.getString(3), Status.valueOf(rs.getString(4)), instant(rs, 5)), assignmentId, ts(now));
    }

    List<MyAssignment> mine(Instant now) {
        return jdbc.query("select r_id, r_class_id, r_class_name, r_lesson_id, r_course_id, r_test_version_id, r_title, r_due_at, r_status, r_submitted_at "
                + "from classroom_my_assignments(?)", (rs, i) -> new MyAssignment(rs.getObject(1, UUID.class), rs.getObject(2, UUID.class), rs.getString(3),
                rs.getObject(4, UUID.class), rs.getObject(5, UUID.class), rs.getObject(6, UUID.class), rs.getString(7), rs.getTimestamp(8).toInstant(),
                Status.valueOf(rs.getString(9)), instant(rs, 10)), ts(now));
    }

    private static Instant instant(ResultSet rs, int col) throws SQLException {
        Timestamp t = rs.getTimestamp(col);
        return t == null ? null : t.toInstant();
    }
}
