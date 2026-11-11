package com.english9x.grading.auto;

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
 * The SQL of automatic scoring, all of it on the grader's connection ({@code e9x_grader_login}) inside a transaction of {@code GraderDatabase}, so every
 * statement below runs under the grader's policies: it sees the SUBMITTED attempt of the person and organisation in the session and the published keys of
 * English9x and that organisation, and writes only {@code scores} and {@code attempt_gradings}.
 */
@Component
class GraderStore {

    /** The attempt as the database says it is; never built from an event. */
    record Attempt(UUID id, UUID userId, UUID orgId, UUID testVersionId, String examCode, Instant submittedAt, boolean interrupted) {}

    /** One question of the attempt with the answer the learner gave (null = none) and the solution side of the pinned version. */
    record Item(UUID questionId, String sectionCode, UUID versionId, String typeCode, JsonNode key, double points, JsonNode answer) {}

    /** What a score row holds. */
    record Row(String skill, Double raw, Double rawMax, Double scaled, UUID scoreTableVersionId) {}

    private final ObjectMapper json;

    GraderStore(ObjectMapper json) {
        this.json = json;
    }

    /** Empty when the attempt is not there for this context, is not SUBMITTED, or has no submission time. */
    Optional<Attempt> attempt(JdbcTemplate j, UUID attemptId) {
        return j.query("select id, user_id, owner_org_id, test_version_id, exam_code, submitted_at, interrupted from exam_attempts where id = ? and status = 'SUBMITTED'",
                (rs, n) -> new Attempt(rs.getObject("id", UUID.class), rs.getObject("user_id", UUID.class), rs.getObject("owner_org_id", UUID.class),
                        rs.getObject("test_version_id", UUID.class), rs.getString("exam_code"),
                        rs.getTimestamp("submitted_at") == null ? null : rs.getTimestamp("submitted_at").toInstant(), rs.getBoolean("interrupted")),
                attemptId).stream().filter(a -> a.submittedAt() != null && a.examCode() != null).findFirst();
    }

    boolean graded(JdbcTemplate j, UUID attemptId) {
        return Boolean.TRUE.equals(j.queryForObject("select exists (select 1 from attempt_gradings where attempt_id = ?)", Boolean.class, attemptId));
    }

    /** The variant (AC / GT) of the test the attempt sat; null for an exam without variants (or a version the context cannot read). */
    String variant(JdbcTemplate j, UUID testVersionId) {
        List<String> found = j.queryForList("select variant from test_versions where id = ?", String.class, testVersionId);
        return found.isEmpty() ? null : found.get(0);
    }

    /** section code to skill. */
    Map<String, String> sectionSkills(JdbcTemplate j, UUID attemptId) {
        Map<String, String> out = new LinkedHashMap<>();
        j.query("select section_code, skill from attempt_sections where attempt_id = ? order by position", rs -> {
            out.put(rs.getString("section_code"), rs.getString("skill"));
        }, attemptId);
        return out;
    }

    /** Every question of the attempt. A question whose version the grader cannot read is a data error, never a free point. */
    List<Item> items(JdbcTemplate j, UUID attemptId) {
        return j.query("""
                select aq.question_id, aq.section_code, aq.question_version_id, v.type_code, v.answer_key::text as k, v.points, r.answer::text as a
                  from attempt_questions aq
                  left join question_versions v on v.id = aq.question_version_id
                  left join responses r on r.attempt_id = aq.attempt_id and r.question_id = aq.question_id
                 where aq.attempt_id = ?
                 order by aq.section_code, aq.question_id
                """, (rs, n) -> {
            UUID versionId = rs.getObject("question_version_id", UUID.class);
            if (rs.getString("type_code") == null) {
                throw new IllegalStateException("question version " + versionId + " of an attempt cannot be read by the grader");
            }
            BigDecimal points = rs.getBigDecimal("points");
            return new Item(rs.getObject("question_id", UUID.class), rs.getString("section_code"), versionId, rs.getString("type_code"),
                    tree(rs.getString("k")), points.doubleValue(), tree(rs.getString("a")));
        }, attemptId);
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

    /** True when this call recorded the grading (and so owns writing the scores); false when it was recorded before. */
    boolean begin(JdbcTemplate j, Attempt a) {
        return j.update("insert into attempt_gradings(attempt_id, user_id, owner_org_id, interrupted) values (?,?,?,?) on conflict (attempt_id) do nothing",
                a.id(), a.userId(), a.orgId(), a.interrupted()) == 1;
    }

    void insertScore(JdbcTemplate j, Attempt a, Row r) {
        j.update("insert into scores(attempt_id, user_id, owner_org_id, skill, kind, source, raw, raw_max, scaled, score_table_version_id) "
                        + "values (?,?,?,?, 'OFFICIAL', 'AUTO', ?,?,?,?) on conflict (attempt_id, skill, kind) do nothing",
                a.id(), a.userId(), a.orgId(), r.skill(), r.raw(), r.rawMax(), r.scaled(), r.scoreTableVersionId());
    }

    /** The displayed score of each skill that has one so far (OFFICIAL over PRELIMINARY), and whether every one of them is OFFICIAL. */
    record Board(Map<String, Double> scores, boolean official) {}

    Board displayed(JdbcTemplate j, UUID attemptId) {
        Map<String, Double> scores = new LinkedHashMap<>();
        boolean[] official = {true};
        j.query("select skill, kind, scaled from scores where attempt_id = ? and scaled is not null order by skill, case kind when 'OFFICIAL' then 0 else 1 end", rs -> {
            if (scores.putIfAbsent(rs.getString("skill"), rs.getBigDecimal("scaled").doubleValue()) == null && !"OFFICIAL".equals(rs.getString("kind"))) {
                official[0] = false;
            }
        }, attemptId);
        return new Board(scores, official[0]);
    }

    /** Locks the grading record (concurrent announcers queue behind it) and says whether AttemptScored still has to be announced. */
    boolean lockUnannounced(JdbcTemplate j, UUID attemptId) {
        List<Boolean> open = j.query("select announced_at is null from attempt_gradings where attempt_id = ? for update", (rs, n) -> rs.getBoolean(1), attemptId);
        return !open.isEmpty() && open.get(0);
    }

    void markAnnounced(JdbcTemplate j, UUID attemptId, Instant at) {
        j.update("update attempt_gradings set announced_at = ? where attempt_id = ? and announced_at is null", Timestamp.from(at), attemptId);
    }
}
