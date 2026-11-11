package com.english9x.classroom.session;

import com.english9x.classroom.session.SessionDtos.Mode;
import com.english9x.classroom.session.SessionDtos.Role;
import com.english9x.classroom.session.SessionDtos.ScheduleItem;
import com.english9x.classroom.session.SessionDtos.SessionView;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.stereotype.Repository;

/** Statements of the schedule endpoints (caller's transaction, caller's Row-Level Security context). */
@Repository
class SessionRepository {

    private static final RowMapper<SessionView> VIEW = (rs, i) -> new SessionView(rs.getObject("id", UUID.class), rs.getObject("class_id", UUID.class),
            Mode.valueOf(rs.getString("mode")), rs.getTimestamp("starts_at").toInstant(), rs.getTimestamp("ends_at").toInstant(), rs.getString("room"), rs.getString("meeting_url"));

    private final JdbcTemplate jdbc;

    SessionRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    SessionView insert(UUID classId, UUID orgId, Mode mode, Instant startsAt, Instant endsAt, String room, String meetingUrl, UUID by) {
        return jdbc.queryForObject("insert into class_sessions(class_id, org_id, mode, starts_at, ends_at, room, meeting_url, created_by) values (?, ?, ?, ?, ?, ?, ?, ?) "
                + "returning id, class_id, mode, starts_at, ends_at, room, meeting_url", VIEW, classId, orgId, mode.name(), Timestamp.from(startsAt), Timestamp.from(endsAt), room, meetingUrl, by);
    }

    /** The sessions of a class the caller may see (a member sees their class's; a manager sees them all), earliest first. */
    List<SessionView> ofClass(UUID classId) {
        return jdbc.query("select id, class_id, mode, starts_at, ends_at, room, meeting_url from class_sessions where class_id = ? order by starts_at, id", VIEW, classId);
    }

    List<ScheduleItem> mine(Instant from, Instant to) {
        return jdbc.query("select r_id, r_class_id, r_class_name, r_mode, r_starts_at, r_ends_at, r_room, r_meeting_url, r_role, r_attendance from classroom_my_schedule(?, ?)",
                (rs, i) -> item(rs), Timestamp.from(from), Timestamp.from(to));
    }

    private static ScheduleItem item(ResultSet rs) throws SQLException {
        return new ScheduleItem(rs.getObject(1, UUID.class), rs.getObject(2, UUID.class), rs.getString(3), Mode.valueOf(rs.getString(4)), rs.getTimestamp(5).toInstant(),
                rs.getTimestamp(6).toInstant(), rs.getString(7), rs.getString(8), Role.valueOf(rs.getString(9)), rs.getString(10));
    }
}
