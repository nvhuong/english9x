package com.english9x.grading.teacher;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.math.BigDecimal;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

/**
 * The SQL of teacher grading that touches a learner's rows, all of it on the grader's connection in the learner's context ({@code GraderDatabase}: user and organisation of
 * the attempt in the session), so the grader sees exactly that attempt. Which learner and organisation come from a queue item the teacher is allowed to see, never from the
 * request.
 */
@Component
class GradingStore {

    /** What the AI said about one response (no cost, no token counts). */
    record Evaluation(UUID questionId, double band, JsonNode result) {}

    record Review(String skill, JsonNode criteria, String comment, double band, UUID teacherId, Instant at) {}

    private final ObjectMapper json;

    GradingStore(ObjectMapper json) {
        this.json = json;
    }

    Map<UUID, Evaluation> evaluations(JdbcTemplate j, UUID attemptId) {
        Map<UUID, Evaluation> out = new LinkedHashMap<>();
        j.query("select question_id, band, result::text as r from ai_evaluations where attempt_id = ? and model <> 'none'", rs -> {
            UUID q = rs.getObject("question_id", UUID.class);
            out.put(q, new Evaluation(q, rs.getBigDecimal("band").doubleValue(), tree(rs.getString("r"))));
        }, attemptId);
        return out;
    }

    /** The AI's score of a skill (PRELIMINARY or OFFICIAL), the base of the deviation. */
    Optional<BigDecimal> aiBand(JdbcTemplate j, UUID attemptId, String skill) {
        return j.queryForList("select scaled from scores where attempt_id = ? and skill = ? and source = 'AI' and scaled is not null", BigDecimal.class, attemptId, skill).stream().findFirst();
    }

    /** True when the skill already has an OFFICIAL score of any source (a teacher cannot add a second one). */
    boolean hasOfficial(JdbcTemplate j, UUID attemptId, String skill) {
        return Boolean.TRUE.equals(j.queryForObject("select exists (select 1 from scores where attempt_id = ? and skill = ? and kind = 'OFFICIAL')", Boolean.class, attemptId, skill));
    }

    List<Review> reviews(JdbcTemplate j, UUID attemptId) {
        return j.query("select skill, criteria::text as c, comment, band, teacher_id, created_at from teacher_reviews where attempt_id = ? order by skill",
                (rs, n) -> new Review(rs.getString("skill"), tree(rs.getString("c")), rs.getString("comment"), rs.getBigDecimal("band").doubleValue(),
                        rs.getObject("teacher_id", UUID.class), rs.getTimestamp("created_at").toInstant()), attemptId);
    }

    void insertReview(JdbcTemplate j, UUID itemId, UUID attemptId, UUID userId, UUID orgId, String skill, UUID teacherId, JsonNode criteria, String comment, BigDecimal band,
            BigDecimal aiBand) {
        BigDecimal deviation = aiBand == null ? null : band.subtract(aiBand);
        j.update("insert into teacher_reviews(queue_item_id, attempt_id, user_id, owner_org_id, skill, teacher_id, criteria, comment, band, ai_band, deviation) values (?,?,?,?,?,?,?::jsonb,?,?,?,?)",
                itemId, attemptId, userId, orgId, skill, teacherId, criteria.toString(), comment, band, aiBand, deviation);
    }

    /** The teacher's score is OFFICIAL and replaces the AI's whatever the mode ({@code domain.grading.OfficialScore}). */
    /** @return false when the skill already has a score of that kind (the AI committed an OFFICIAL one meanwhile): nothing was written */
    boolean insertScore(JdbcTemplate j, UUID attemptId, UUID userId, UUID orgId, String skill, String kind, BigDecimal band) {
        return j.update("insert into scores(attempt_id, user_id, owner_org_id, skill, kind, source, raw, raw_max, scaled) values (?,?,?,?,?, 'TEACHER', ?, null, ?) on conflict (attempt_id, skill, kind) do nothing",
                attemptId, userId, orgId, skill, kind, band, band) == 1;
    }

    int reviewedSkills(JdbcTemplate j, UUID attemptId) {
        Integer n = j.queryForObject("select count(*) from teacher_reviews where attempt_id = ?", Integer.class, attemptId);
        return n == null ? 0 : n;
    }

    /** The item as the grader sees it in the learner's context: its version, and whether it is CLAIMED by that teacher. */
    Optional<Integer> claimedVersion(JdbcTemplate j, UUID itemId, UUID teacherId) {
        return j.queryForList("select version from grading_queue_items where id = ? and state = 'CLAIMED' and claimed_by = ?", Integer.class, itemId, teacherId).stream().findFirst();
    }

    boolean markDone(JdbcTemplate j, UUID itemId, int version, Instant at) {
        return j.update("update grading_queue_items set state = 'DONE', graded_at = ?, version = version + 1 where id = ? and version = ? and state = 'CLAIMED'", Timestamp.from(at), itemId, version) == 1;
    }

    /** Creates the item of an attempt (a no-op when it exists). Under the grader's policy the side must be the one {@code grading_queue_side} says. @return the id, empty when it existed */
    Optional<UUID> insertItem(JdbcTemplate j, UUID attemptId, String reason) {
        return j.queryForList("insert into grading_queue_items(attempt_id, user_id, owner_org_id, grading_org_id, exam_code, reason) "
                + "select a.id, a.user_id, a.owner_org_id, grading_queue_side(a.owner_org_id), a.exam_code, ? from exam_attempts a where a.id = ? and a.status = 'SUBMITTED' and a.exam_code is not null "
                + "on conflict (attempt_id) do nothing returning id", UUID.class, reason, attemptId).stream().findFirst();
    }

    String examOf(JdbcTemplate j, UUID itemId) {
        return j.queryForList("select exam_code from grading_queue_items where id = ?", String.class, itemId).stream().findFirst().orElse(null);
    }

    JsonNode tree(String text) {
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
