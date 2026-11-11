package com.english9x.grading.ai;

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
 * The SQL of AI grading, all of it on the grader's connection inside a transaction of {@code GraderDatabase} (role {@code e9x_grader}, the attempt's person and
 * organisation in the session). That is also how the PUBLISHED grading skill is read: the learner's own role has no policy on skills (P2.1-fix1 M3), the grader has
 * the PUBLISHED rows of English9x and of the organisation bound in {@code app.org_id}.
 */
@Component
public class AiStore {

    public record Attempt(UUID id, UUID userId, UUID orgId, UUID testVersionId, String examCode, Instant submittedAt) {}

    /** One question of the attempt's test, with what the learner gave. {@code recordingPath} is the staged key of a Speaking recording (null = none). */
    public record Item(UUID questionId, String sectionCode, UUID versionId, String typeCode, JsonNode content, double points, JsonNode answer, String recordingPath, String recordingMime) {}

    public record Skill(UUID versionId, String rubric, JsonNode criteria, String promptTemplate, JsonNode samples, JsonNode resultSchema) {}

    private final ObjectMapper json;

    AiStore(ObjectMapper json) {
        this.json = json;
    }

    public Optional<Attempt> attempt(JdbcTemplate j, UUID attemptId) {
        return j.query("select id, user_id, owner_org_id, test_version_id, exam_code, submitted_at from exam_attempts where id = ? and status = 'SUBMITTED'",
                (rs, n) -> new Attempt(rs.getObject("id", UUID.class), rs.getObject("user_id", UUID.class), rs.getObject("owner_org_id", UUID.class),
                        rs.getObject("test_version_id", UUID.class), rs.getString("exam_code"),
                        rs.getTimestamp("submitted_at") == null ? null : rs.getTimestamp("submitted_at").toInstant()),
                attemptId).stream().filter(a -> a.submittedAt() != null && a.examCode() != null).findFirst();
    }

    public Map<String, String> sectionSkills(JdbcTemplate j, UUID attemptId) {
        Map<String, String> out = new LinkedHashMap<>();
        j.query("select section_code, skill from attempt_sections where attempt_id = ? order by position", rs -> {
            out.put(rs.getString("section_code"), rs.getString("skill"));
        }, attemptId);
        return out;
    }

    /** Every question of the attempt's test (membership is the test version's, as in the automatic scoring), with the answer and the recording the learner gave. */
    public List<Item> items(JdbcTemplate j, UUID attemptId, UUID testVersionId) {
        return j.query("""
                select m.section_code, m.question_version_id, v.type_code, v.content::text as c, v.points,
                       (select r.answer::text from attempt_questions aq
                          join responses r on r.attempt_id = aq.attempt_id and r.question_id = aq.question_id
                         where aq.attempt_id = ? and aq.question_version_id = m.question_version_id and aq.section_code = m.section_code
                         order by r.answered_at desc, aq.question_id limit 1) as a,
                       (select aq.question_id from attempt_questions aq
                         where aq.attempt_id = ? and aq.question_version_id = m.question_version_id and aq.section_code = m.section_code
                         order by aq.question_id limit 1) as question_id
                  from (select distinct tp.section_code, i.question_version_id
                          from test_parts tp
                          join test_part_groups g on g.test_version_id = tp.test_version_id and g.part_position = tp.position
                          join question_group_items i on i.group_version_id = g.group_version_id
                         where tp.test_version_id = ?) m
                  left join question_versions v on v.id = m.question_version_id
                 order by m.section_code, m.question_version_id
                """, (rs, n) -> {
            UUID versionId = rs.getObject("question_version_id", UUID.class);
            if (rs.getString("type_code") == null) {
                throw new IllegalStateException("question version " + versionId + " of the test of attempt " + attemptId + " cannot be read by the grader");
            }
            return new Item(rs.getObject("question_id", UUID.class), rs.getString("section_code"), versionId, rs.getString("type_code"), tree(rs.getString("c")),
                    rs.getBigDecimal("points").doubleValue(), tree(rs.getString("a")), null, null);
        }, attemptId, attemptId, testVersionId).stream().map(i -> withRecording(j, attemptId, i)).toList();
    }

    private Item withRecording(JdbcTemplate j, UUID attemptId, Item i) {
        if (i.questionId() == null) {
            return i;
        }
        List<String[]> rec = j.query("select storage_path, mime_type from speaking_recordings where attempt_id = ? and question_id = ?",
                (rs, n) -> new String[] {rs.getString(1), rs.getString(2)}, attemptId, i.questionId());
        return rec.isEmpty() ? i : new Item(i.questionId(), i.sectionCode(), i.versionId(), i.typeCode(), i.content(), i.points(), i.answer(), rec.get(0)[0], rec.get(0)[1]);
    }

    /** The PUBLISHED skill of the question type the grader may read: the bound organisation's own when it has one, else English9x's; the newest version. */
    public Optional<Skill> skill(JdbcTemplate j, String questionTypeCode) {
        return j.query("""
                select v.id, v.rubric, v.criteria::text as criteria, v.prompt_template, v.samples::text as samples, v.result_schema::text as result_schema
                  from grading_skill_versions v join grading_skills s on s.id = v.grading_skill_id
                 where s.question_type_code = ? and v.status = 'PUBLISHED'
                 order by (v.owner_org_id is null), v.version_no desc limit 1
                """, (rs, n) -> new Skill(rs.getObject("id", UUID.class), rs.getString("rubric"), tree(rs.getString("criteria")), rs.getString("prompt_template"),
                tree(rs.getString("samples")), tree(rs.getString("result_schema"))), questionTypeCode).stream().findFirst();
    }

    /** question id to band of the evaluations already stored. */
    public Map<UUID, Double> evaluated(JdbcTemplate j, UUID attemptId) {
        Map<UUID, Double> out = new LinkedHashMap<>();
        j.query("select question_id, band from ai_evaluations where attempt_id = ?", rs -> {
            out.put(rs.getObject("question_id", UUID.class), rs.getBigDecimal("band").doubleValue());
        }, attemptId);
        return out;
    }

    void insertEvaluation(JdbcTemplate j, Attempt a, UUID questionId, UUID skillVersionId, String model, int in, int out, BigDecimal cost, double band, JsonNode result) {
        j.update("insert into ai_evaluations(attempt_id, question_id, user_id, owner_org_id, skill_version_id, model, input_tokens, output_tokens, cost_vnd, band, result) "
                + "values (?,?,?,?,?,?,?,?,?,?,?::jsonb) on conflict (attempt_id, question_id) do nothing",
                a.id(), questionId, a.userId(), a.orgId(), skillVersionId, model, in, out, cost, band, result.toString());
    }

    void insertScore(JdbcTemplate j, Attempt a, String skill, String kind, double band) {
        j.update("insert into scores(attempt_id, user_id, owner_org_id, skill, kind, source, raw, raw_max, scaled) values (?,?,?,?,?, 'AI', ?, null, ?) "
                + "on conflict (attempt_id, skill, kind) do nothing", a.id(), a.userId(), a.orgId(), skill, kind, band, band);
    }

    public Optional<String> state(JdbcTemplate j, UUID attemptId) {
        return j.queryForList("select state from ai_gradings where attempt_id = ?", String.class, attemptId).stream().findFirst();
    }

    /** DONE, also over an earlier AI_FAILED (a late run, a re-run). */
    void markDone(JdbcTemplate j, Attempt a) {
        j.update("insert into ai_gradings(attempt_id, user_id, owner_org_id, state) values (?,?,?, 'DONE') "
                + "on conflict (attempt_id) do update set state = 'DONE', error = null, updated_at = now() where ai_gradings.state = 'AI_FAILED'", a.id(), a.userId(), a.orgId());
    }

    /** Records AI_FAILED unless the attempt already has a state. @return true when this call recorded it */
    boolean markFailed(JdbcTemplate j, Attempt a, String error) {
        return j.update("insert into ai_gradings(attempt_id, user_id, owner_org_id, state, error) values (?,?,?, 'AI_FAILED', ?) on conflict (attempt_id) do nothing",
                a.id(), a.userId(), a.orgId(), error == null ? null : error.substring(0, Math.min(error.length(), 1000))) == 1;
    }

    boolean routeToTeachers(JdbcTemplate j, Attempt a) {
        return j.update("insert into ai_gradings(attempt_id, user_id, owner_org_id, state) values (?,?,?, 'TEACHER_QUEUE') on conflict (attempt_id) do nothing",
                a.id(), a.userId(), a.orgId()) == 1;
    }

    /** Counted under the bound organisation's RLS: the limit is effectively per user per organisation (acceptable for an emergency switch). */
    /** Attempts of the person that the AI took (or tried) since {@code since}, in this context. */
    int aiAttemptsSince(JdbcTemplate j, UUID userId, Instant since) {
        Integer n = j.queryForObject("select count(*) from ai_gradings where user_id = ? and state <> 'TEACHER_QUEUE' and created_at >= ?", Integer.class, userId, Timestamp.from(since));
        return n == null ? 0 : n;
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
