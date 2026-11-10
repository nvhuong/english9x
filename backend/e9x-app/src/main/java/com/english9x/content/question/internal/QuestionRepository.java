package com.english9x.content.question.internal;

import com.english9x.content.question.api.QuestionExamView;
import com.english9x.content.question.api.QuestionVersionView;
import com.english9x.content.question.api.QuestionVersions;
import com.english9x.workflow.api.ContentStatus;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.math.BigDecimal;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;

/**
 * {@code questions} and {@code question_versions}, always in the caller's transaction: row-level security (the AUTHOR or REVIEWER of the exam, in the
 * owner's context) is the access rule, so a row outside it is simply not there. The learner's view comes from the {@code e9x_question_exam_view}
 * function and has no answer-side column.
 */
@Repository
class QuestionRepository implements QuestionVersions {

    record Header(UUID id, UUID ownerOrgId, String examCode, String typeCode, UUID groupId, UUID createdBy) {}

    /** A row of the question list: the latest version of a question, without the solution side. */
    record Summary(UUID questionId, UUID versionId, int versionNo, ContentStatus status, String examCode, String typeCode, String title,
                   BigDecimal points, UUID createdBy, Instant updatedAt) {}

    record Filter(String examCode, String typeCode, ContentStatus status, UUID groupId) {}

    private static final String VERSION_COLUMNS = "id, question_id, version_no, status, owner_org_id, exam_code, type_code, content, answer_key, "
            + "explanation, points, created_by, created_at, updated_at";

    private final JdbcTemplate jdbc;
    private final ObjectMapper json;
    private final RowMapper<QuestionVersionView> versionMapper;

    QuestionRepository(JdbcTemplate jdbc, ObjectMapper json) {
        this.jdbc = jdbc;
        this.json = json;
        this.versionMapper = (rs, n) -> new QuestionVersionView(rs.getObject("id", UUID.class), rs.getObject("question_id", UUID.class),
                rs.getInt("version_no"), ContentStatus.valueOf(rs.getString("status")),
                Optional.ofNullable(rs.getObject("owner_org_id", UUID.class)), rs.getString("exam_code"), rs.getString("type_code"),
                tree(rs, "content"), tree(rs, "answer_key"), rs.getString("explanation"), rs.getBigDecimal("points"),
                rs.getObject("created_by", UUID.class), instant(rs.getTimestamp("created_at")), instant(rs.getTimestamp("updated_at")));
    }

    private JsonNode tree(ResultSet rs, String column) throws SQLException {
        try {
            return json.readTree(rs.getString(column));
        } catch (JsonProcessingException e) {
            throw new SQLException("column " + column + " is not JSON", e);
        }
    }

    private String text(JsonNode node) {
        try {
            return json.writeValueAsString(node);
        } catch (JsonProcessingException e) {
            throw new IllegalArgumentException("not serialisable", e);
        }
    }

    private static Instant instant(Timestamp t) {
        return t == null ? null : t.toInstant();
    }

    // ---- reads --------------------------------------------------------------------------------------------------------------------

    Optional<Header> header(UUID questionId) {
        return jdbc.query("select id, owner_org_id, exam_code, type_code, group_id, created_by from questions where id = ?",
                (rs, n) -> new Header(rs.getObject(1, UUID.class), rs.getObject(2, UUID.class), rs.getString(3), rs.getString(4),
                        rs.getObject(5, UUID.class), rs.getObject(6, UUID.class)), questionId).stream().findFirst();
    }

    Optional<QuestionVersionView> latest(UUID questionId) {
        return jdbc.query("select " + VERSION_COLUMNS + " from question_versions where question_id = ? order by version_no desc limit 1",
                versionMapper, questionId).stream().findFirst();
    }

    Optional<QuestionVersionView> versionNo(UUID questionId, int versionNo) {
        return jdbc.query("select " + VERSION_COLUMNS + " from question_versions where question_id = ? and version_no = ?",
                versionMapper, questionId, versionNo).stream().findFirst();
    }

    List<Summary> list(Filter f, int limit, int offset) {
        List<Object> args = new ArrayList<>();
        String where = where(f, args);
        args.add(limit);
        args.add(offset);
        return jdbc.query("select * from (select distinct on (question_id) id, question_id, version_no, status, exam_code, type_code, "
                + "left(content->>'stem', 160) as title, points, created_by, updated_at from question_versions"
                + where + " order by question_id, version_no desc) latest "
                + (f.status() == null ? "" : "where status = '" + f.status().name() + "' ")
                + "order by updated_at desc, id limit ? offset ?",
                (rs, n) -> new Summary(rs.getObject("question_id", UUID.class), rs.getObject("id", UUID.class), rs.getInt("version_no"),
                        ContentStatus.valueOf(rs.getString("status")), rs.getString("exam_code"), rs.getString("type_code"), rs.getString("title"),
                        rs.getBigDecimal("points"), rs.getObject("created_by", UUID.class), instant(rs.getTimestamp("updated_at"))),
                args.toArray());
    }

    long count(Filter f) {
        List<Object> args = new ArrayList<>();
        String where = where(f, args);
        Long n = jdbc.queryForObject("select count(*) from (select distinct on (question_id) question_id, status from question_versions"
                + where + " order by question_id, version_no desc) latest "
                + (f.status() == null ? "" : "where status = '" + f.status().name() + "'"), Long.class, args.toArray());
        return n == null ? 0 : n;
    }

    /** The filter on the header columns copied to the version (applied before "latest", they are the same for every version of a question). */
    private static String where(Filter f, List<Object> args) {
        StringBuilder sb = new StringBuilder();
        String glue = " where ";
        if (f.examCode() != null) {
            sb.append(glue).append("exam_code = ?");
            args.add(f.examCode());
            glue = " and ";
        }
        if (f.typeCode() != null) {
            sb.append(glue).append("type_code = ?");
            args.add(f.typeCode());
            glue = " and ";
        }
        if (f.groupId() != null) {
            sb.append(glue).append("question_id in (select id from questions where group_id = ?)");
            args.add(f.groupId());
        }
        return sb.toString();
    }

    // ---- the QuestionVersions API ---------------------------------------------------------------------------------------------------

    @Override
    @Transactional(readOnly = true)
    public Optional<QuestionExamView> examView(UUID questionVersionId) {
        return jdbc.query("select version_id, question_id, exam_code, type_code, content, points from e9x_question_exam_view(?)",
                (rs, n) -> new QuestionExamView(rs.getObject(1, UUID.class), rs.getObject(2, UUID.class), rs.getString(3), rs.getString(4),
                        tree(rs, "content"), rs.getBigDecimal(6)), questionVersionId).stream().findFirst();
    }

    @Override
    @Transactional(readOnly = true)
    public Optional<QuestionVersionView> find(UUID questionVersionId) {
        return jdbc.query("select " + VERSION_COLUMNS + " from question_versions where id = ?", versionMapper, questionVersionId).stream().findFirst();
    }

    @Override
    @Transactional(readOnly = true)
    public List<QuestionVersionView> versions(UUID questionId) {
        return jdbc.query("select " + VERSION_COLUMNS + " from question_versions where question_id = ? order by version_no", versionMapper, questionId);
    }

    // ---- writes ---------------------------------------------------------------------------------------------------------------------

    void insertHeader(Header h) {
        jdbc.update("insert into questions(id, owner_org_id, exam_code, type_code, group_id, created_by) values (?,?,?,?,?,?)",
                h.id(), h.ownerOrgId(), h.examCode(), h.typeCode(), h.groupId(), h.createdBy());
    }

    void insertVersion(UUID id, Header h, int versionNo, JsonNode content, JsonNode answerKey, String explanation, BigDecimal points, UUID by) {
        jdbc.update("insert into question_versions(id, question_id, owner_org_id, exam_code, type_code, version_no, content, answer_key, explanation, points, created_by) "
                        + "values (?,?,?,?,?,?,?::jsonb,?::jsonb,?,?,?)",
                id, h.id(), h.ownerOrgId(), h.examCode(), h.typeCode(), versionNo, text(content), text(answerKey), explanation, points, by);
    }

    /** Rewrites the content side and the solution side of an editable version; 0 when it is not (or no longer) a DRAFT / CHANGES_REQUESTED of the caller. */
    int updateDraft(UUID versionId, JsonNode content, JsonNode answerKey, String explanation, BigDecimal points) {
        return jdbc.update("update question_versions set content = ?::jsonb, answer_key = ?::jsonb, explanation = ?, points = ?, updated_at = now() "
                + "where id = ? and status in ('DRAFT', 'CHANGES_REQUESTED')", text(content), text(answerKey), explanation, points, versionId);
    }
}
