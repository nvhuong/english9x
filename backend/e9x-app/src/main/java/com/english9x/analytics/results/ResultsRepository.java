package com.english9x.analytics.results;

import com.english9x.analytics.results.ResultsDtos.TypeRate;
import com.english9x.grading.api.DisplayedScores;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

/**
 * SQL of the results screen, all on the request role: Row-Level Security answers for whose rows are visible (a person's attempts, scores, evaluations, reviews, statistics are
 * theirs alone). The answer key is not here: it is not readable by this role (see {@code AttemptSolutions}).
 */
@Repository
class ResultsRepository {

    record Head(UUID id, UUID userId, UUID orgId, String examCode, String status, boolean interrupted, Instant submittedAt) {}

    record Ai(UUID questionId, String skill, double band, JsonNode result) {}

    record Review(String skill, JsonNode criteria, String comment, double band) {}

    record Lesson(String typeCode, UUID courseId, String courseTitle, UUID lessonId, String title) {}

    private final JdbcTemplate jdbc;
    private final ObjectMapper json;

    ResultsRepository(JdbcTemplate jdbc, ObjectMapper json) {
        this.jdbc = jdbc;
        this.json = json;
    }

    /** The attempt, only when it is the person's. */
    Optional<Head> head(UUID attemptId, UUID userId) {
        return jdbc.query("select id, user_id, owner_org_id, exam_code, status, interrupted, submitted_at from exam_attempts where id = ? and user_id = ?",
                (rs, n) -> new Head(rs.getObject("id", UUID.class), rs.getObject("user_id", UUID.class), rs.getObject("owner_org_id", UUID.class), rs.getString("exam_code"),
                        rs.getString("status"), rs.getBoolean("interrupted"), rs.getTimestamp("submitted_at") == null ? null : rs.getTimestamp("submitted_at").toInstant()),
                attemptId, userId).stream().findFirst();
    }

    boolean graded(UUID attemptId) {
        return Boolean.TRUE.equals(jdbc.queryForObject("select exists (select 1 from attempt_gradings where attempt_id = ?)", Boolean.class, attemptId));
    }

    /** The skills the attempt held, in the order of its sections. */
    List<String> skills(UUID attemptId) {
        return jdbc.queryForList("select skill from attempt_sections where attempt_id = ? and skill is not null group by skill order by min(position)", String.class, attemptId);
    }

    /** Every stored score with a converted value or points; {@code DisplayedScores} picks the one shown. */
    List<Stored> scores(UUID attemptId) {
        return jdbc.query("select skill, kind, source, raw, raw_max, scaled from scores where attempt_id = ?",
                (rs, n) -> new Stored(rs.getString("skill"), rs.getString("kind"), rs.getString("source"), dbl(rs.getBigDecimal("raw")), dbl(rs.getBigDecimal("raw_max")),
                        dbl(rs.getBigDecimal("scaled"))), attemptId);
    }

    record Stored(String skill, String kind, String source, Double raw, Double rawMax, Double scaled) {
        DisplayedScores.Candidate candidate() {
            return new DisplayedScores.Candidate(skill, kind, source, scaled == null ? 0 : scaled);
        }
    }

    private static Double dbl(java.math.BigDecimal v) {
        return v == null ? null : v.doubleValue();
    }

    /** What the AI said, per response, with the skill of the section the question was sat in. */
    List<Ai> evaluations(UUID attemptId) {
        return jdbc.query("""
                select e.question_id, s.skill, e.band, e.result::text as r
                  from ai_evaluations e
                  join attempt_questions q on q.attempt_id = e.attempt_id and q.question_id = e.question_id
                  join attempt_sections s on s.attempt_id = q.attempt_id and s.section_code = q.section_code
                 where e.attempt_id = ? and e.model <> 'none'
                 order by s.position, e.question_id
                """, (rs, n) -> new Ai(rs.getObject("question_id", UUID.class), rs.getString("skill"), rs.getBigDecimal("band").doubleValue(), tree(rs.getString("r"))), attemptId);
    }

    List<Review> reviews(UUID attemptId) {
        return jdbc.query("select skill, criteria::text as c, comment, band from teacher_reviews where attempt_id = ? order by skill",
                (rs, n) -> new Review(rs.getString("skill"), tree(rs.getString("c")), rs.getString("comment"), rs.getBigDecimal("band").doubleValue()), attemptId);
    }

    /** The display names of question types. */
    Map<String, String> typeNames() {
        Map<String, String> out = new LinkedHashMap<>();
        jdbc.query("select code, name from question_types", rs -> {
            out.put(rs.getString("code"), rs.getString("name"));
        });
        return out;
    }

    /** The share of right answers per question type over every scored attempt of the person (of the exam when given), worst first. */
    List<TypeRate> typeRates(UUID userId, String exam) {
        return jdbc.query("""
                select s.type_code, coalesce(t.name, s.type_code) as name, count(*) filter (where s.correct) as right_n, count(*) as total
                  from item_type_stats s left join question_types t on t.code = s.type_code
                 where s.user_id = ? and (?::text is null or s.exam_code = ?)
                 group by s.type_code, t.name
                 order by (count(*) filter (where s.correct))::float8 / count(*), count(*) desc, s.type_code
                """, (rs, n) -> {
            int right = rs.getInt("right_n");
            int total = rs.getInt("total");
            return new TypeRate(rs.getString("type_code"), rs.getString("name"), right, total, (double) right / total);
        }, userId, exam, exam);
    }

    /** The same aggregate for ANOTHER person over one organisation's attempts: the SECURITY DEFINER answer of V202611170001 (the organisation's ORG_ADMIN / TEACHER only), worst first. */
    List<TypeRate> typeRatesOf(UUID userId, UUID orgId) {
        return jdbc.query("select type_code, type_name, right_n, total from results_type_rates_of(?, ?)",
                (rs, n) -> new TypeRate(rs.getString("type_code"), rs.getString("type_name"), rs.getInt("right_n"), rs.getInt("total"),
                        (double) rs.getInt("right_n") / rs.getInt("total")), userId, orgId);
    }

    int attemptsWithStats(UUID userId, String exam) {
        Integer n = jdbc.queryForObject("select count(distinct attempt_id) from item_type_stats where user_id = ? and (?::text is null or exam_code = ?)", Integer.class, userId, exam, exam);
        return n == null ? 0 : n;
    }

    /** The method lessons of those types in the courses the person holds an entitlement to at {@code now} (the narrow SECURITY DEFINER answer of V202611160001). */
    List<Lesson> methodLessons(String exam, List<String> types, Instant now) {
        if (types.isEmpty()) {
            return List.of();
        }
        List<Lesson> out = new ArrayList<>();
        jdbc.query("select type_code, course_id, course_title, course_lesson_id, lesson_title from results_method_lessons(?, ?::text[], ?)", rs -> {
            out.add(new Lesson(rs.getString("type_code"), rs.getObject("course_id", UUID.class), rs.getString("course_title"), rs.getObject("course_lesson_id", UUID.class),
                    rs.getString("lesson_title")));
        }, exam, types.toArray(new String[0]), Timestamp.from(now));
        return out;
    }

    private JsonNode tree(String text) {
        if (text == null) {
            return null;
        }
        try {
            return json.readTree(text);
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("a stored JSON document cannot be read", e);
        }
    }
}
