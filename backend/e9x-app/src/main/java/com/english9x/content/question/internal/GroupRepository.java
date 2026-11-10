package com.english9x.content.question.internal;

import com.english9x.content.question.api.QuestionGroupExamView;
import com.english9x.content.question.api.QuestionGroupVersionView;
import com.english9x.content.question.api.QuestionGroupVersions;
import com.english9x.workflow.api.ContentStatus;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;

/** {@code question_groups}, {@code question_group_versions} and their members, in the caller's transaction (see {@link QuestionRepository}). */
@Repository
class GroupRepository implements QuestionGroupVersions {

    record Header(UUID id, UUID ownerOrgId, String examCode, UUID createdBy) {}

    record Summary(UUID groupId, UUID versionId, int versionNo, ContentStatus status, String examCode, String title, int members, UUID createdBy,
                   Instant updatedAt) {}

    private static final String COLUMNS = "id, group_id, version_no, status, owner_org_id, exam_code, content, solution, created_by, created_at, updated_at";

    private final JdbcTemplate jdbc;
    private final ObjectMapper json;
    private final RowMapper<QuestionGroupVersionView> mapper;

    GroupRepository(JdbcTemplate jdbc, ObjectMapper json) {
        this.jdbc = jdbc;
        this.json = json;
        this.mapper = (rs, n) -> new QuestionGroupVersionView(rs.getObject("id", UUID.class), rs.getObject("group_id", UUID.class), rs.getInt("version_no"),
                ContentStatus.valueOf(rs.getString("status")), Optional.ofNullable(rs.getObject("owner_org_id", UUID.class)), rs.getString("exam_code"),
                tree(rs, "content"), tree(rs, "solution"), members(rs.getObject("id", UUID.class)), rs.getObject("created_by", UUID.class),
                instant(rs.getTimestamp("created_at")), instant(rs.getTimestamp("updated_at")));
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

    List<UUID> members(UUID groupVersionId) {
        return jdbc.query("select question_version_id from question_group_items where group_version_id = ? order by position",
                (rs, n) -> rs.getObject(1, UUID.class), groupVersionId);
    }

    Optional<Header> header(UUID groupId) {
        return jdbc.query("select id, owner_org_id, exam_code, created_by from question_groups where id = ?",
                (rs, n) -> new Header(rs.getObject(1, UUID.class), rs.getObject(2, UUID.class), rs.getString(3), rs.getObject(4, UUID.class)), groupId)
                .stream().findFirst();
    }

    Optional<QuestionGroupVersionView> latest(UUID groupId) {
        return jdbc.query("select " + COLUMNS + " from question_group_versions where group_id = ? order by version_no desc limit 1", mapper, groupId)
                .stream().findFirst();
    }

    Optional<QuestionGroupVersionView> versionNo(UUID groupId, int versionNo) {
        return jdbc.query("select " + COLUMNS + " from question_group_versions where group_id = ? and version_no = ?", mapper, groupId, versionNo)
                .stream().findFirst();
    }

    List<QuestionGroupVersionView> versions(UUID groupId) {
        return jdbc.query("select " + COLUMNS + " from question_group_versions where group_id = ? order by version_no", mapper, groupId);
    }

    List<Summary> list(String examCode, ContentStatus status, int limit, int offset) {
        return jdbc.query("select * from (select distinct on (v.group_id) v.id, v.group_id, v.version_no, v.status, v.exam_code, left(v.content->>'title', 160) as title, "
                + "(select count(*) from question_group_items i where i.group_version_id = v.id) as members, v.created_by, v.updated_at "
                + "from question_group_versions v " + (examCode == null ? "" : "where v.exam_code = ? ") + "order by v.group_id, v.version_no desc) latest "
                + (status == null ? "" : "where status = '" + status.name() + "' ") + "order by updated_at desc, id limit ? offset ?",
                (rs, n) -> new Summary(rs.getObject("group_id", UUID.class), rs.getObject("id", UUID.class), rs.getInt("version_no"),
                        ContentStatus.valueOf(rs.getString("status")), rs.getString("exam_code"), rs.getString("title"), rs.getInt("members"),
                        rs.getObject("created_by", UUID.class), instant(rs.getTimestamp("updated_at"))),
                examCode == null ? new Object[] {limit, offset} : new Object[] {examCode, limit, offset});
    }

    // ---- the QuestionGroupVersions API ------------------------------------------------------------------------------------------------

    @Override
    @Transactional(readOnly = true)
    public Optional<QuestionGroupExamView> examView(UUID groupVersionId) {
        return jdbc.query("select version_id, group_id, exam_code, content, question_version_ids from e9x_question_group_exam_view(?)",
                (rs, n) -> new QuestionGroupExamView(rs.getObject(1, UUID.class), rs.getObject(2, UUID.class), rs.getString(3), tree(rs, "content"),
                        List.of((UUID[]) rs.getArray(5).getArray())), groupVersionId).stream().findFirst();
    }

    @Override
    @Transactional(readOnly = true)
    public Optional<QuestionGroupVersionView> find(UUID groupVersionId) {
        return jdbc.query("select " + COLUMNS + " from question_group_versions where id = ?", mapper, groupVersionId).stream().findFirst();
    }

    // ---- writes -------------------------------------------------------------------------------------------------------------------------

    void insertHeader(Header h) {
        jdbc.update("insert into question_groups(id, owner_org_id, exam_code, created_by) values (?,?,?,?)", h.id(), h.ownerOrgId(), h.examCode(), h.createdBy());
    }

    void insertVersion(UUID id, Header h, int versionNo, JsonNode content, JsonNode solution, List<UUID> members, UUID by) {
        jdbc.update("insert into question_group_versions(id, group_id, owner_org_id, exam_code, version_no, content, solution, created_by) values (?,?,?,?,?,?::jsonb,?::jsonb,?)",
                id, h.id(), h.ownerOrgId(), h.examCode(), versionNo, text(content), text(solution), by);
        insertMembers(id, h, members);
    }

    private void insertMembers(UUID versionId, Header h, List<UUID> members) {
        for (int i = 0; i < members.size(); i++) {
            jdbc.update("insert into question_group_items(group_version_id, position, question_version_id, owner_org_id, exam_code) values (?,?,?,?,?)",
                    versionId, i + 1, members.get(i), h.ownerOrgId(), h.examCode());
        }
    }

    /** Rewrites an editable version: 0 when it is not (or no longer) a DRAFT / CHANGES_REQUESTED. The members are replaced (the child guard allows it only then). */
    int updateDraft(UUID versionId, Header h, JsonNode content, JsonNode solution, List<UUID> members) {
        int rows = jdbc.update("update question_group_versions set content = ?::jsonb, solution = ?::jsonb, updated_at = now() "
                + "where id = ? and status in ('DRAFT', 'CHANGES_REQUESTED')", text(content), text(solution), versionId);
        if (rows == 1) {
            jdbc.update("delete from question_group_items where group_version_id = ?", versionId);
            insertMembers(versionId, h, members);
        }
        return rows;
    }
}
