package com.english9x.classroom.attendance;

import com.english9x.classroom.attendance.AttendanceDtos.Mark;
import com.english9x.classroom.attendance.AttendanceDtos.ReportRow;
import com.english9x.classroom.attendance.AttendanceDtos.Row;
import com.english9x.classroom.attendance.AttendanceDtos.Status;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

/** Statements of the attendance endpoints (caller's transaction, caller's Row-Level Security context). */
@Repository
class AttendanceRepository {

    private final JdbcTemplate jdbc;

    AttendanceRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    /** The class of a session of this organisation, as far as the caller may see it. */
    Optional<UUID> classOf(UUID sessionId, UUID orgId) {
        return jdbc.query("select class_id from class_sessions where id = ? and org_id = ?", (rs, i) -> rs.getObject(1, UUID.class), sessionId, orgId).stream().findFirst();
    }

    List<UUID> memberIds(UUID classId) {
        return jdbc.queryForList("select user_id from class_members where class_id = ?", UUID.class, classId);
    }

    void upsert(UUID sessionId, UUID orgId, Mark m, UUID by) {
        jdbc.update("insert into attendance(session_id, org_id, user_id, status, note, marked_by) values (?, ?, ?, ?, ?, ?) "
                + "on conflict (session_id, user_id) do update set status = excluded.status, note = excluded.note, marked_by = excluded.marked_by",
                sessionId, orgId, m.userId(), m.status().name(), m.note() == null || m.note().isBlank() ? null : m.note().trim(), by);
    }

    List<Row> sheet(UUID sessionId) {
        return jdbc.query("select r_user_id, r_display_name, r_email, r_status, r_note, r_marked_at from classroom_session_attendance(?)",
                (rs, i) -> new Row(rs.getObject(1, UUID.class), rs.getString(2), rs.getString(3), status(rs, 4), rs.getString(5), instant(rs, 6)), sessionId);
    }

    List<ReportRow> report(UUID classId) {
        return jdbc.query("select r_user_id, r_display_name, r_email, r_present, r_late, r_absent, r_excused, r_rate from classroom_attendance_report(?)",
                (rs, i) -> new ReportRow(rs.getObject(1, UUID.class), rs.getString(2), rs.getString(3), rs.getInt(4), rs.getInt(5), rs.getInt(6), rs.getInt(7),
                        rs.getObject(8) == null ? null : rs.getDouble(8)), classId);
    }

    private static Status status(ResultSet rs, int col) throws SQLException {
        String s = rs.getString(col);
        return s == null ? null : Status.valueOf(s);
    }

    private static Instant instant(ResultSet rs, int col) throws SQLException {
        Timestamp t = rs.getTimestamp(col);
        return t == null ? null : t.toInstant();
    }
}
