package com.english9x.learning.internal;

import com.english9x.domain.learning.DailyActivity;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.math.BigDecimal;
import java.sql.Timestamp;
import java.time.Instant;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

/** The SQL of the learning module, all under the row-level security of the person bound to the transaction. */
@Repository
class LearningRepository {

    record Lesson(UUID id, UUID versionId, UUID courseId, String kind, UUID refId, String title, Integer maxAttempts, double passThreshold, String unlockRule) {}

    record Key(UUID courseId, UUID refId, UUID questionVersionId, String typeCode, String kind, JsonNode answerKey, String explanation, double points) {}

    record VersionHead(String title, String rule) {}

    record LessonRow(UUID id, String kind, String title, UUID refId) {}

    record Progress(boolean completed, Double bestScore) {}

    private final JdbcTemplate jdbc;
    private final ObjectMapper json;

    LearningRepository(JdbcTemplate jdbc, ObjectMapper json) {
        this.jdbc = jdbc;
        this.json = json;
    }

    private JsonNode tree(String text) {
        try {
            return text == null ? null : json.readTree(text);
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("a stored JSON document cannot be read", e);
        }
    }

    private String text(JsonNode node) {
        try {
            return json.writeValueAsString(node == null ? json.nullNode() : node);
        } catch (JsonProcessingException e) {
            throw new IllegalArgumentException(e);
        }
    }

    Optional<Lesson> lesson(UUID lessonId) {
        return jdbc.query("""
                select l.id, l.course_version_id, v.course_id, l.kind, l.ref_id, l.title, l.max_attempts, c.pass_threshold, v.unlock_rule
                  from course_lessons l join course_versions v on v.id = l.course_version_id
                  join course_chapters c on c.course_version_id = l.course_version_id and c.position = l.chapter_position
                 where l.id = ?
                """, (rs, n) -> new Lesson(rs.getObject(1, UUID.class), rs.getObject(2, UUID.class), rs.getObject(3, UUID.class), rs.getString(4),
                rs.getObject(5, UUID.class), rs.getString(6), (Integer) rs.getObject(7), rs.getBigDecimal(8).doubleValue(), rs.getString(9)), lessonId).stream().findFirst();
    }

    Optional<Key> practiceKey(UUID lessonId, UUID questionVersionId, Instant now) {
        return jdbc.query("select * from learning_practice_key(?, ?, ?)", (rs, n) -> new Key(rs.getObject("course_id", UUID.class),
                rs.getObject("lesson_ref_id", UUID.class), questionVersionId, rs.getString("type_code"), rs.getString("kind"), tree(rs.getString("answer_key")),
                rs.getString("explanation"), rs.getBigDecimal("points").doubleValue()), lessonId, questionVersionId, Timestamp.from(now)).stream().findFirst();
    }

    List<Key> testKeys(UUID lessonId, Instant now) {
        return jdbc.query("select * from learning_test_keys(?, ?)", (rs, n) -> new Key(rs.getObject("course_id", UUID.class), rs.getObject("ref_id", UUID.class),
                rs.getObject("question_version_id", UUID.class), rs.getString("type_code"), rs.getString("kind"), tree(rs.getString("answer_key")), null,
                rs.getBigDecimal("points").doubleValue()), lessonId, Timestamp.from(now));
    }

    void insertPractice(UUID user, UUID course, UUID lessonRef, Key key, JsonNode answer, boolean correct, double earned, Instant now) {
        jdbc.update("insert into practice_attempts(user_id, course_id, lesson_ref_id, question_version_id, type_code, answer, correct, earned, points, answered_at) "
                + "values (?,?,?,?,?,?::jsonb,?,?,?,?)", user, course, lessonRef, key.questionVersionId(), key.typeCode(), text(answer), correct, earned, key.points(), Timestamp.from(now));
    }

    // ---- progress ----

    Progress progress(UUID user, UUID course, UUID ref) {
        return jdbc.query("select completed, best_score from lesson_progress where user_id = ? and course_id = ? and ref_id = ?",
                (rs, n) -> new Progress(rs.getBoolean(1), rs.getBigDecimal(2) == null ? null : rs.getBigDecimal(2).doubleValue()), user, course, ref).stream().findFirst()
                .orElse(new Progress(false, null));
    }

    /** Makes sure the row exists and locks it (concurrent attempts of one person queue behind it). */
    void lockProgress(UUID user, UUID course, UUID ref, String kind) {
        jdbc.update("insert into lesson_progress(user_id, course_id, ref_id, kind) values (?,?,?,?) on conflict do nothing", user, course, ref, kind);
        jdbc.queryForList("select 1 from lesson_progress where user_id = ? and course_id = ? and ref_id = ? for update", user, course, ref);
    }

    /** @return true when this call is the one that completed it */
    boolean complete(UUID user, UUID course, UUID ref, Instant now) {
        return jdbc.update("update lesson_progress set completed = true, completed_at = ?, updated_at = ? where user_id = ? and course_id = ? and ref_id = ? and completed = false",
                Timestamp.from(now), Timestamp.from(now), user, course, ref) == 1;
    }

    void setBest(UUID user, UUID course, UUID ref, double best, Instant now) {
        jdbc.update("update lesson_progress set best_score = ?, completed = true, completed_at = coalesce(completed_at, ?), updated_at = ? where user_id = ? and course_id = ? and ref_id = ?",
                BigDecimal.valueOf(best), Timestamp.from(now), Timestamp.from(now), user, course, ref);
    }

    int attemptsUsed(UUID user, UUID course, UUID ref) {
        Integer n = jdbc.queryForObject("select count(*) from chapter_test_attempts where user_id = ? and course_id = ? and ref_id = ?", Integer.class, user, course, ref);
        return n == null ? 0 : n;
    }

    void insertAttempt(UUID user, UUID course, UUID ref, int no, double raw, double rawMax, double score, boolean passed, JsonNode answers, Instant now) {
        jdbc.update("insert into chapter_test_attempts(user_id, course_id, ref_id, attempt_no, raw, raw_max, score, passed, answers, submitted_at) values (?,?,?,?,?,?,?,?,?::jsonb,?)",
                user, course, ref, no, raw, rawMax, BigDecimal.valueOf(score), passed, text(answers), Timestamp.from(now));
    }

    // ---- study time ----

    String timeZone(UUID user) {
        return jdbc.queryForList("select time_zone from study_streaks where user_id = ?", String.class, user).stream().findFirst().orElse(null);
    }

    void setTimeZone(UUID user, String zone, Instant now) {
        jdbc.update("insert into study_streaks(user_id, time_zone) values (?,?) on conflict (user_id) do update set time_zone = excluded.time_zone, updated_at = ?",
                user, zone, Timestamp.from(now));
    }

    void heartbeat(UUID user, Instant at, LocalDate day, UUID course, UUID lesson) {
        jdbc.update("insert into study_heartbeats(user_id, at, day, course_id, lesson_id) values (?,?,?,?,?) on conflict do nothing", user, Timestamp.from(at), day, course, lesson);
    }

    /** The latest signal of the person (any day), or null. */
    Instant lastHeartbeat(UUID user) {
        return jdbc.query("select max(at) from study_heartbeats where user_id = ?", rs -> rs.next() && rs.getTimestamp(1) != null ? rs.getTimestamp(1).toInstant() : null, user);
    }

    List<Instant> heartbeats(UUID user, LocalDate day) {
        return jdbc.query("select at from study_heartbeats where user_id = ? and day = ? order by at", (rs, n) -> rs.getTimestamp(1).toInstant(), user, day);
    }

    void setSeconds(UUID user, LocalDate day, int seconds, Instant now) {
        jdbc.update("insert into study_time_daily(user_id, day, seconds) values (?,?,?) on conflict (user_id, day) do update set seconds = greatest(study_time_daily.seconds, excluded.seconds), updated_at = ?",
                user, day, seconds, Timestamp.from(now));
    }

    void addLessonCompleted(UUID user, LocalDate day, Instant now) {
        jdbc.update("insert into study_time_daily(user_id, day, lessons_completed) values (?,?,1) on conflict (user_id, day) do update set lessons_completed = study_time_daily.lessons_completed + 1, updated_at = ?",
                user, day, Timestamp.from(now));
    }

    int seconds(UUID user, LocalDate day) {
        return jdbc.queryForList("select seconds from study_time_daily where user_id = ? and day = ?", Integer.class, user, day).stream().findFirst().orElse(0);
    }

    List<DailyActivity> days(UUID user, LocalDate since) {
        List<DailyActivity> out = new ArrayList<>();
        jdbc.query("select day, seconds, lessons_completed from study_time_daily where user_id = ? and day >= ?",
                rs -> {
                    out.add(new DailyActivity(rs.getDate(1).toLocalDate(), rs.getInt(2) / 60, rs.getInt(3)));
                }, user, since);
        return out;
    }

    int[] streak(UUID user) {
        return jdbc.query("select current_days, longest_days from study_streaks where user_id = ?", (rs, n) -> new int[] {rs.getInt(1), rs.getInt(2)}, user).stream().findFirst()
                .orElse(new int[] {0, 0});
    }

    void setStreak(UUID user, int current, int longest, LocalDate today, Instant now) {
        jdbc.update("insert into study_streaks(user_id, current_days, longest_days, last_day) values (?,?,?,?) on conflict (user_id) do update set current_days = excluded.current_days, "
                + "longest_days = greatest(study_streaks.longest_days, excluded.longest_days), last_day = excluded.last_day, updated_at = ?", user, current, longest, today, Timestamp.from(now));
    }

    VersionHead versionHead(UUID version) {
        return jdbc.query("select title, unlock_rule from course_versions where id = ?", (rs, n) -> new VersionHead(rs.getString(1), rs.getString(2)), version).stream().findFirst()
                .orElseThrow(LearningProblems::notFound);
    }

    List<LessonRow> lessonsOf(UUID version) {
        return jdbc.query("select id, kind, title, ref_id from course_lessons where course_version_id = ?",
                (rs, n) -> new LessonRow(rs.getObject(1, UUID.class), rs.getString(2), rs.getString(3), rs.getObject(4, UUID.class)), version);
    }

    List<String> chapterTitles(UUID version) {
        return jdbc.queryForList("select title from course_chapters where course_version_id = ? order by position", String.class, version);
    }
}
