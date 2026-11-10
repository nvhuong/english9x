package com.english9x.content.skill.internal;

import com.english9x.content.skill.api.GradingSkillVersionView;
import com.english9x.content.skill.api.GradingSkillVersions;
import com.english9x.workflow.api.ContentStatus;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
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

/** {@code grading_skills} and {@code grading_skill_versions}, in the caller's transaction: row-level security is the access rule. */
@Repository
class SkillRepository implements GradingSkillVersions {

    record Header(UUID id, UUID ownerOrgId, String examCode, String questionTypeCode) {}

    record Summary(UUID skillId, UUID versionId, int versionNo, ContentStatus status, String examCode, String questionTypeCode, UUID createdBy, Instant updatedAt) {}

    record Filter(String examCode, ContentStatus status) {}

    /** What a skill version holds besides its identity. */
    record Content(String rubric, JsonNode criteria, String promptTemplate, JsonNode samples, JsonNode resultSchema) {}

    private static final String FROM = " from grading_skill_versions v join grading_skills s on s.id = v.grading_skill_id ";
    private static final String COLUMNS = "v.id, v.grading_skill_id, v.version_no, v.status, v.owner_org_id, v.exam_code, s.question_type_code, v.rubric, "
            + "v.criteria::text as criteria, v.prompt_template, v.samples::text as samples, v.result_schema::text as result_schema, v.created_by, v.created_at, v.updated_at";
    private static final RowMapper<Header> HEADER = (rs, n) -> new Header(rs.getObject(1, UUID.class), rs.getObject(2, UUID.class), rs.getString(3), rs.getString(4));

    private final JdbcTemplate jdbc;
    private final ObjectMapper json;
    private final RowMapper<GradingSkillVersionView> mapper;

    SkillRepository(JdbcTemplate jdbc, ObjectMapper json) {
        this.jdbc = jdbc;
        this.json = json;
        this.mapper = (rs, n) -> new GradingSkillVersionView(rs.getObject("id", UUID.class), rs.getObject("grading_skill_id", UUID.class), rs.getInt("version_no"),
                ContentStatus.valueOf(rs.getString("status")), Optional.ofNullable(rs.getObject("owner_org_id", UUID.class)), rs.getString("exam_code"),
                rs.getString("question_type_code"), rs.getString("rubric"), tree(rs.getString("criteria")), rs.getString("prompt_template"),
                tree(rs.getString("samples")), tree(rs.getString("result_schema")), rs.getObject("created_by", UUID.class), instant(rs.getTimestamp("created_at")),
                instant(rs.getTimestamp("updated_at")));
    }

    private JsonNode tree(String text) throws SQLException {
        try {
            return json.readTree(text);
        } catch (JsonProcessingException e) {
            throw new SQLException("not JSON", e);
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

    Optional<Header> header(UUID skillId) {
        return jdbc.query("select id, owner_org_id, exam_code, question_type_code from grading_skills where id = ?", HEADER, skillId).stream().findFirst();
    }

    Optional<Header> forType(String questionTypeCode, UUID ownerOrgId) {
        return jdbc.query("select id, owner_org_id, exam_code, question_type_code from grading_skills where question_type_code = ? and owner_org_id is not distinct from ?",
                HEADER, questionTypeCode, ownerOrgId).stream().findFirst();
    }

    Optional<GradingSkillVersionView> latest(UUID skillId) {
        return jdbc.query("select " + COLUMNS + FROM + "where v.grading_skill_id = ? order by v.version_no desc limit 1", mapper, skillId).stream().findFirst();
    }

    Optional<GradingSkillVersionView> versionNo(UUID skillId, int versionNo) {
        return jdbc.query("select " + COLUMNS + FROM + "where v.grading_skill_id = ? and v.version_no = ?", mapper, skillId, versionNo).stream().findFirst();
    }

    List<GradingSkillVersionView> versions(UUID skillId) {
        return jdbc.query("select " + COLUMNS + FROM + "where v.grading_skill_id = ? order by v.version_no", mapper, skillId);
    }

    List<Summary> list(Filter f, int limit, int offset) {
        List<Object> args = new ArrayList<>();
        String where = where(f, args);
        args.add(limit);
        args.add(offset);
        return jdbc.query("select * from (select distinct on (v.grading_skill_id) v.id, v.grading_skill_id, v.version_no, v.status, v.exam_code, s.question_type_code, "
                + "v.created_by, v.updated_at" + FROM + where + " order by v.grading_skill_id, v.version_no desc) latest "
                + (f.status() == null ? "" : "where status = '" + f.status().name() + "' ") + "order by updated_at desc, id limit ? offset ?",
                (rs, n) -> new Summary(rs.getObject("grading_skill_id", UUID.class), rs.getObject("id", UUID.class), rs.getInt("version_no"),
                        ContentStatus.valueOf(rs.getString("status")), rs.getString("exam_code"), rs.getString("question_type_code"),
                        rs.getObject("created_by", UUID.class), instant(rs.getTimestamp("updated_at"))), args.toArray());
    }

    long count(Filter f) {
        List<Object> args = new ArrayList<>();
        Long n = jdbc.queryForObject("select count(*) from (select distinct on (v.grading_skill_id) v.grading_skill_id, v.status" + FROM + where(f, args)
                + " order by v.grading_skill_id, v.version_no desc) latest " + (f.status() == null ? "" : "where status = '" + f.status().name() + "'"),
                Long.class, args.toArray());
        return n == null ? 0 : n;
    }

    private static String where(Filter f, List<Object> args) {
        if (f.examCode() == null) {
            return "where true";
        }
        args.add(f.examCode());
        return "where v.exam_code = ?";
    }

    // ---- the GradingSkillVersions API -----------------------------------------------------------------------------------------------

    @Override
    @Transactional(readOnly = true)
    public Optional<GradingSkillVersionView> find(UUID gradingSkillVersionId) {
        return jdbc.query("select " + COLUMNS + FROM + "where v.id = ?", mapper, gradingSkillVersionId).stream().findFirst();
    }

    // ---- writes ---------------------------------------------------------------------------------------------------------------------

    void insertHeader(Header h) {
        jdbc.update("insert into grading_skills(id, owner_org_id, exam_code, question_type_code) values (?,?,?,?)", h.id(), h.ownerOrgId(), h.examCode(), h.questionTypeCode());
    }

    void insertVersion(UUID id, Header h, int versionNo, Content c, UUID by) {
        jdbc.update("insert into grading_skill_versions(id, grading_skill_id, owner_org_id, exam_code, version_no, rubric, criteria, prompt_template, samples, result_schema, created_by) "
                + "values (?,?,?,?,?,?,?::jsonb,?,?::jsonb,?::jsonb,?)",
                id, h.id(), h.ownerOrgId(), h.examCode(), versionNo, c.rubric(), text(c.criteria()), c.promptTemplate(), text(c.samples()), text(c.resultSchema()), by);
    }

    int updateDraft(UUID versionId, Content c) {
        return jdbc.update("update grading_skill_versions set rubric = ?, criteria = ?::jsonb, prompt_template = ?, samples = ?::jsonb, result_schema = ?::jsonb, updated_at = now() "
                + "where id = ? and status in ('DRAFT', 'CHANGES_REQUESTED')",
                c.rubric(), text(c.criteria()), c.promptTemplate(), text(c.samples()), text(c.resultSchema()), versionId);
    }
}
