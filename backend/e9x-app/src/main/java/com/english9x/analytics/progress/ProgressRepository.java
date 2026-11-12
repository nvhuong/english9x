package com.english9x.analytics.progress;

import com.english9x.grading.api.DisplayedScores;
import java.sql.Timestamp;
import java.time.Instant;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

/**
 * SQL of the progress screen. Every read is a SECURITY DEFINER function of V202611160002 that first asks {@code progress_viewable}: the person themselves, or (for an
 * organisation) its ORG_ADMIN / TEACHER for a member of that organisation, and nobody else.
 */
@Repository
class ProgressRepository {

    record Plan(UUID entitlementId, UUID courseId, String code, String title, Instant from, Instant until, String source, UUID groupId, String planName, Integer planMax,
            String org) {}

    record Course(UUID courseId, int lessonsTotal, int lessonsDone, int testsTotal, int testsDone, int chaptersTotal, int chaptersDone, UUID resume, Instant lastActivity) {}

    record Row(UUID attemptId, UUID courseId, String exam, Instant submittedAt, DisplayedScores.Candidate score) {}

    record Pending(UUID id, String className, Instant dueAt, UUID courseId) {}

    record Streak(int current, int longest, LocalDate lastDay, String zone) {}

    private final JdbcTemplate jdbc;

    ProgressRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    private static Instant at(java.sql.ResultSet rs, String c) throws java.sql.SQLException {
        Timestamp t = rs.getTimestamp(c);
        return t == null ? null : t.toInstant();
    }

    boolean viewable(UUID user, UUID org) {
        return Boolean.TRUE.equals(jdbc.queryForObject("select progress_viewable(?, ?)", Boolean.class, user, org));
    }

    List<Plan> plans(UUID user, UUID org) {
        return jdbc.query("select * from progress_plans(?, ?)", (rs, n) -> new Plan(rs.getObject("entitlement_id", UUID.class), rs.getObject("course_id", UUID.class),
                rs.getString("course_code"), rs.getString("course_title"), at(rs, "valid_from"), at(rs, "valid_until"), rs.getString("source"), rs.getObject("group_id", UUID.class),
                rs.getString("plan_name"), (Integer) rs.getObject("plan_max"), rs.getString("org_name")), user, org);
    }

    List<Course> courses(UUID user, UUID org) {
        return jdbc.query("select * from progress_courses(?, ?)", (rs, n) -> new Course(rs.getObject("course_id", UUID.class), rs.getInt("lessons_total"), rs.getInt("lessons_done"),
                rs.getInt("tests_total"), rs.getInt("tests_done"), rs.getInt("chapters_total"), rs.getInt("chapters_done"), rs.getObject("resume_lesson_id", UUID.class),
                at(rs, "last_activity")), user, org);
    }

    /** One row per submitted attempt and stored score of it (an attempt with no converted score has one row with a null score). */
    List<Row> attempts(UUID user, UUID org) {
        return jdbc.query("select * from progress_attempts(?, ?)", (rs, n) -> new Row(rs.getObject("attempt_id", UUID.class), rs.getObject("course_id", UUID.class),
                rs.getString("exam_code"), at(rs, "submitted_at"), rs.getString("skill") == null ? null
                        : new DisplayedScores.Candidate(rs.getString("skill"), rs.getString("kind"), rs.getString("source"), rs.getBigDecimal("scaled").doubleValue())), user, org);
    }

    List<Pending> pending(UUID user, UUID org) {
        return jdbc.query("select * from progress_pending(?, ?)", (rs, n) -> new Pending(rs.getObject("assignment_id", UUID.class), rs.getString("class_name"), at(rs, "due_at"),
                rs.getObject("course_id", UUID.class)), user, org);
    }

    /** day to seconds. */
    List<Object[]> study(UUID user, UUID org, LocalDate from) {
        List<Object[]> out = new ArrayList<>();
        jdbc.query("select * from progress_study(?, ?, ?)", rs -> {
            out.add(new Object[] {rs.getDate("day").toLocalDate(), rs.getInt("seconds")});
        }, user, org, java.sql.Date.valueOf(from));
        return out;
    }

    Optional<Streak> streak(UUID user, UUID org) {
        return jdbc.query("select * from progress_streak(?, ?)", (rs, n) -> new Streak(rs.getInt("current_days"), rs.getInt("longest_days"),
                rs.getDate("last_day") == null ? null : rs.getDate("last_day").toLocalDate(), rs.getString("time_zone")), user, org).stream().findFirst();
    }
}
