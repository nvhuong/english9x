package com.english9x.content.strategy.internal;

import com.english9x.content.strategy.api.StrategyLessonExamView;
import com.english9x.content.strategy.api.StrategyLessonVersionView;
import com.english9x.content.strategy.api.StrategyLessonVersions;
import com.english9x.workflow.api.ContentStatus;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;

/** {@code strategy_lessons}, {@code strategy_lesson_versions} and {@code strategy_lesson_types}, in the caller's transaction: row-level security is the access rule. */
@Repository
class StrategyRepository implements StrategyLessonVersions {

    record Header(UUID id, UUID ownerOrgId, String examCode, UUID createdBy) {}

    record Summary(UUID lessonId, UUID versionId, int versionNo, ContentStatus status, String examCode, String title, UUID createdBy, Instant updatedAt) {}

    record Filter(String examCode, ContentStatus status) {}

    private static final String COLUMNS = "v.id, v.lesson_id, v.version_no, v.status, v.owner_org_id, v.exam_code, v.title, v.blocks::text as blocks, "
            + "coalesce((select array_agg(t.type_code order by t.type_code) from strategy_lesson_types t where t.version_id = v.id), '{}') as types, "
            + "v.created_by, v.created_at, v.updated_at";

    private final JdbcTemplate jdbc;
    private final ObjectMapper json;
    private final RowMapper<StrategyLessonVersionView> mapper;

    StrategyRepository(JdbcTemplate jdbc, ObjectMapper json) {
        this.jdbc = jdbc;
        this.json = json;
        this.mapper = (rs, n) -> new StrategyLessonVersionView(rs.getObject("id", UUID.class), rs.getObject("lesson_id", UUID.class), rs.getInt("version_no"),
                ContentStatus.valueOf(rs.getString("status")), Optional.ofNullable(rs.getObject("owner_org_id", UUID.class)), rs.getString("exam_code"),
                rs.getString("title"), tree(rs.getString("blocks")), Arrays.asList((String[]) rs.getArray("types").getArray()),
                rs.getObject("created_by", UUID.class), instant(rs.getTimestamp("created_at")), instant(rs.getTimestamp("updated_at")));
    }

    private JsonNode tree(String text) throws SQLException {
        try {
            return json.readTree(text);
        } catch (JsonProcessingException e) {
            throw new SQLException("blocks are not JSON", e);
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

    Optional<Header> header(UUID lessonId) {
        return jdbc.query("select id, owner_org_id, exam_code, created_by from strategy_lessons where id = ?",
                (rs, n) -> new Header(rs.getObject(1, UUID.class), rs.getObject(2, UUID.class), rs.getString(3), rs.getObject(4, UUID.class)), lessonId)
                .stream().findFirst();
    }

    Optional<StrategyLessonVersionView> latest(UUID lessonId) {
        return jdbc.query("select " + COLUMNS + " from strategy_lesson_versions v where v.lesson_id = ? order by v.version_no desc limit 1", mapper, lessonId)
                .stream().findFirst();
    }

    Optional<StrategyLessonVersionView> versionNo(UUID lessonId, int versionNo) {
        return jdbc.query("select " + COLUMNS + " from strategy_lesson_versions v where v.lesson_id = ? and v.version_no = ?", mapper, lessonId, versionNo)
                .stream().findFirst();
    }

    List<StrategyLessonVersionView> versions(UUID lessonId) {
        return jdbc.query("select " + COLUMNS + " from strategy_lesson_versions v where v.lesson_id = ? order by v.version_no", mapper, lessonId);
    }

    List<Summary> list(Filter f, int limit, int offset) {
        List<Object> args = new ArrayList<>();
        String where = where(f, args);
        args.add(limit);
        args.add(offset);
        return jdbc.query("select * from (select distinct on (lesson_id) id, lesson_id, version_no, status, exam_code, title, created_by, updated_at "
                + "from strategy_lesson_versions" + where + " order by lesson_id, version_no desc) latest "
                + (f.status() == null ? "" : "where status = '" + f.status().name() + "' ") + "order by updated_at desc, id limit ? offset ?",
                (rs, n) -> new Summary(rs.getObject("lesson_id", UUID.class), rs.getObject("id", UUID.class), rs.getInt("version_no"),
                        ContentStatus.valueOf(rs.getString("status")), rs.getString("exam_code"), rs.getString("title"),
                        rs.getObject("created_by", UUID.class), instant(rs.getTimestamp("updated_at"))), args.toArray());
    }

    long count(Filter f) {
        List<Object> args = new ArrayList<>();
        Long n = jdbc.queryForObject("select count(*) from (select distinct on (lesson_id) lesson_id, status from strategy_lesson_versions" + where(f, args)
                + " order by lesson_id, version_no desc) latest " + (f.status() == null ? "" : "where status = '" + f.status().name() + "'"), Long.class, args.toArray());
        return n == null ? 0 : n;
    }

    private static String where(Filter f, List<Object> args) {
        if (f.examCode() == null) {
            return "";
        }
        args.add(f.examCode());
        return " where exam_code = ?";
    }

    // ---- the StrategyLessonVersions API ---------------------------------------------------------------------------------------------

    @Override
    @Transactional(readOnly = true)
    public Optional<StrategyLessonExamView> examView(UUID lessonVersionId) {
        return jdbc.query("select version_id, lesson_id, exam_code, title, blocks::text, question_type_codes from e9x_strategy_lesson_exam_view(?)",
                (rs, n) -> new StrategyLessonExamView(rs.getObject(1, UUID.class), rs.getObject(2, UUID.class), rs.getString(3), rs.getString(4),
                        tree(rs.getString(5)), Arrays.asList((String[]) rs.getArray(6).getArray())), lessonVersionId).stream().findFirst();
    }

    @Override
    @Transactional(readOnly = true)
    public Optional<StrategyLessonVersionView> find(UUID lessonVersionId) {
        return jdbc.query("select " + COLUMNS + " from strategy_lesson_versions v where v.id = ?", mapper, lessonVersionId).stream().findFirst();
    }

    // ---- writes ---------------------------------------------------------------------------------------------------------------------

    void insertHeader(Header h) {
        jdbc.update("insert into strategy_lessons(id, owner_org_id, exam_code, created_by) values (?,?,?,?)", h.id(), h.ownerOrgId(), h.examCode(), h.createdBy());
    }

    void insertVersion(UUID id, Header h, int versionNo, String title, JsonNode blocks, List<String> typeCodes, UUID by) {
        jdbc.update("insert into strategy_lesson_versions(id, lesson_id, owner_org_id, exam_code, version_no, title, blocks, created_by) values (?,?,?,?,?,?,?::jsonb,?)",
                id, h.id(), h.ownerOrgId(), h.examCode(), versionNo, title, text(blocks), by);
        insertTypes(id, h, typeCodes);
    }

    int updateDraft(UUID versionId, Header h, String title, JsonNode blocks, List<String> typeCodes) {
        int n = jdbc.update("update strategy_lesson_versions set title = ?, blocks = ?::jsonb, updated_at = now() where id = ? and status in ('DRAFT', 'CHANGES_REQUESTED')",
                title, text(blocks), versionId);
        if (n == 1) {
            jdbc.update("delete from strategy_lesson_types where version_id = ?", versionId);
            insertTypes(versionId, h, typeCodes);
        }
        return n;
    }

    private void insertTypes(UUID versionId, Header h, List<String> typeCodes) {
        for (String code : typeCodes) {
            jdbc.update("insert into strategy_lesson_types(version_id, type_code, owner_org_id, exam_code) values (?,?,?,?)", versionId, code, h.ownerOrgId(), h.examCode());
        }
    }
}
