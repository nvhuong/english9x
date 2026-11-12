package com.english9x.analytics.reports;

import com.english9x.analytics.reports.ReportsDtos.Assignments;
import com.english9x.analytics.reports.ReportsDtos.Attendance;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

/**
 * SQL of the class report: it only asks the classroom's own SECURITY DEFINER functions (the roster, the attendance report, the assignment reports), which each answer for the people who
 * manage the class and for the current organisation alone. The class is read under Row-Level Security with the owner organisation in the WHERE.
 */
@Repository
class ReportsRepository {

    record ClassRow(UUID id, String name, String kind, boolean canManage) {}

    record Member(UUID userId, String displayName, String email) {}

    private final JdbcTemplate jdbc;

    ReportsRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    Optional<ClassRow> find(UUID orgId, UUID classId) {
        return jdbc.query("select c.id, c.name, c.kind, classroom_manages(c.id) as can_manage from classes c where c.owner_org_id = ? and c.id = ?",
                (rs, i) -> new ClassRow(rs.getObject("id", UUID.class), rs.getString("name"), rs.getString("kind"), rs.getBoolean("can_manage")), orgId, classId).stream().findFirst();
    }

    List<Member> members(UUID classId) {
        return jdbc.query("select r_user_id, r_display_name, r_email from classroom_class_members(?)",
                (rs, i) -> new Member(rs.getObject(1, UUID.class), rs.getString(2), rs.getString(3)), classId);
    }

    /** Per member: the status of every assignment of the class. */
    Map<UUID, Assignments> assignments(UUID classId, Instant now) {
        Map<UUID, int[]> counts = new LinkedHashMap<>();
        Timestamp at = Timestamp.from(now);
        jdbc.query("select r.r_user_id, r.r_status, count(*)::int from classroom_class_assignments(?, ?) a "
                + "cross join lateral classroom_assignment_report(a.r_id, ?) r group by r.r_user_id, r.r_status", rs -> {
            int[] c = counts.computeIfAbsent(rs.getObject(1, UUID.class), k -> new int[4]);
            int n = rs.getInt(3);
            switch (rs.getString(2)) {
                case "SUBMITTED" -> c[0] += n;
                case "LATE" -> c[1] += n;
                case "MISSING" -> c[2] += n;
                default -> c[3] += n;
            }
        }, classId, at, at);
        Map<UUID, Assignments> out = new LinkedHashMap<>();
        counts.forEach((u, c) -> out.put(u, new Assignments(c[0] + c[1] + c[2] + c[3], c[0], c[1], c[2], c[3])));
        return out;
    }

    Map<UUID, Attendance> attendance(UUID classId) {
        Map<UUID, Attendance> out = new LinkedHashMap<>();
        jdbc.query("select r_user_id, r_present, r_late, r_absent, r_excused, r_rate from classroom_attendance_report(?)", rs -> {
            out.put(rs.getObject(1, UUID.class), new Attendance(rs.getInt(2), rs.getInt(3), rs.getInt(4), rs.getInt(5), rs.getObject(6) == null ? null : rs.getDouble(6)));
        }, classId);
        return out;
    }
}
