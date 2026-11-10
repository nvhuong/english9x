package com.english9x.content.scoring.internal;

import com.english9x.content.scoring.api.ScoreRow;
import com.english9x.content.scoring.api.ScoreScope;
import com.english9x.content.scoring.api.ScoreTableVersionView;
import com.english9x.content.scoring.api.ScoreTableVersions;
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

/** {@code score_tables} and {@code score_table_versions}, in the caller's transaction: row-level security is the access rule. */
@Repository
class ScoreTableRepository implements ScoreTableVersions {

    record Header(UUID id, UUID ownerOrgId, String examCode, String skill, ScoreScope scope, UUID testId, String function) {}

    record Summary(UUID tableId, UUID versionId, int versionNo, ContentStatus status, String examCode, String skill, ScoreScope scope, String function,
                   int rowCount, UUID createdBy, Instant updatedAt) {}

    record Filter(String examCode, ContentStatus status) {}

    private static final String FROM = " from score_table_versions v join score_tables t on t.id = v.score_table_id ";
    private static final String COLUMNS = "v.id, v.score_table_id, v.version_no, v.status, v.owner_org_id, v.exam_code, t.skill, t.scope, t.test_id, t.function, "
            + "v.entries::text as entries, v.created_by, v.created_at, v.updated_at";

    private final JdbcTemplate jdbc;
    private final ObjectMapper json;
    private final RowMapper<ScoreTableVersionView> mapper;

    ScoreTableRepository(JdbcTemplate jdbc, ObjectMapper json) {
        this.jdbc = jdbc;
        this.json = json;
        this.mapper = (rs, n) -> new ScoreTableVersionView(rs.getObject("id", UUID.class), rs.getObject("score_table_id", UUID.class), rs.getInt("version_no"),
                ContentStatus.valueOf(rs.getString("status")), Optional.ofNullable(rs.getObject("owner_org_id", UUID.class)), rs.getString("exam_code"),
                rs.getString("skill"), ScoreScope.valueOf(rs.getString("scope")), Optional.ofNullable(rs.getObject("test_id", UUID.class)),
                rs.getString("function"), rows(rs.getString("entries")), rs.getObject("created_by", UUID.class), instant(rs.getTimestamp("created_at")),
                instant(rs.getTimestamp("updated_at")));
    }

    private List<ScoreRow> rows(String text) throws SQLException {
        try {
            List<ScoreRow> out = new ArrayList<>();
            for (JsonNode e : json.readTree(text)) {
                out.add(new ScoreRow(e.path("raw").asInt(), e.path("scaled").asDouble()));
            }
            return out;
        } catch (JsonProcessingException e) {
            throw new SQLException("entries are not JSON", e);
        }
    }

    private String text(List<ScoreRow> rows) {
        try {
            return json.writeValueAsString(rows);
        } catch (JsonProcessingException e) {
            throw new IllegalArgumentException("not serialisable", e);
        }
    }

    private static Instant instant(Timestamp t) {
        return t == null ? null : t.toInstant();
    }

    Optional<Header> header(UUID tableId) {
        return jdbc.query("select id, owner_org_id, exam_code, skill, scope, test_id, function from score_tables where id = ?", HEADER, tableId)
                .stream().findFirst();
    }

    /** The English9x or organisation table of this exam and skill at EXAM scope, if the caller can see it. */
    Optional<Header> examDefault(String examCode, String skill, UUID ownerOrgId) {
        return jdbc.query("select id, owner_org_id, exam_code, skill, scope, test_id, function from score_tables "
                + "where scope = 'EXAM' and exam_code = ? and skill = ? and owner_org_id is not distinct from ?", HEADER, examCode, skill, ownerOrgId)
                .stream().findFirst();
    }

    private static final RowMapper<Header> HEADER = (rs, n) -> new Header(rs.getObject(1, UUID.class), rs.getObject(2, UUID.class), rs.getString(3),
            rs.getString(4), ScoreScope.valueOf(rs.getString(5)), rs.getObject(6, UUID.class), rs.getString(7));

    Optional<ScoreTableVersionView> latest(UUID tableId) {
        return jdbc.query("select " + COLUMNS + FROM + "where v.score_table_id = ? order by v.version_no desc limit 1", mapper, tableId).stream().findFirst();
    }

    Optional<ScoreTableVersionView> versionNo(UUID tableId, int versionNo) {
        return jdbc.query("select " + COLUMNS + FROM + "where v.score_table_id = ? and v.version_no = ?", mapper, tableId, versionNo).stream().findFirst();
    }

    List<ScoreTableVersionView> versions(UUID tableId) {
        return jdbc.query("select " + COLUMNS + FROM + "where v.score_table_id = ? order by v.version_no", mapper, tableId);
    }

    List<Summary> list(Filter f, int limit, int offset) {
        List<Object> args = new ArrayList<>();
        String where = where(f, args);
        args.add(limit);
        args.add(offset);
        return jdbc.query("select * from (select distinct on (v.score_table_id) v.id, v.score_table_id, v.version_no, v.status, v.exam_code, t.skill, t.scope, "
                + "t.function, jsonb_array_length(v.entries) as n, v.created_by, v.updated_at" + FROM + where
                + " order by v.score_table_id, v.version_no desc) latest " + (f.status() == null ? "" : "where status = '" + f.status().name() + "' ")
                + "order by updated_at desc, id limit ? offset ?",
                (rs, n) -> new Summary(rs.getObject("score_table_id", UUID.class), rs.getObject("id", UUID.class), rs.getInt("version_no"),
                        ContentStatus.valueOf(rs.getString("status")), rs.getString("exam_code"), rs.getString("skill"), ScoreScope.valueOf(rs.getString("scope")),
                        rs.getString("function"), rs.getInt("n"), rs.getObject("created_by", UUID.class), instant(rs.getTimestamp("updated_at"))), args.toArray());
    }

    long count(Filter f) {
        List<Object> args = new ArrayList<>();
        Long n = jdbc.queryForObject("select count(*) from (select distinct on (v.score_table_id) v.score_table_id, v.status" + FROM + where(f, args)
                + " order by v.score_table_id, v.version_no desc) latest " + (f.status() == null ? "" : "where status = '" + f.status().name() + "'"),
                Long.class, args.toArray());
        return n == null ? 0 : n;
    }

    private static String where(Filter f, List<Object> args) {
        if (f.examCode() == null) {
            return "";
        }
        args.add(f.examCode());
        return "where v.exam_code = ?";
    }

    // ---- the ScoreTableVersions API -------------------------------------------------------------------------------------------------

    @Override
    @Transactional(readOnly = true)
    public Optional<ScoreTableVersionView> find(UUID scoreTableVersionId) {
        return jdbc.query("select " + COLUMNS + FROM + "where v.id = ?", mapper, scoreTableVersionId).stream().findFirst();
    }

    // ---- writes ---------------------------------------------------------------------------------------------------------------------

    void insertHeader(Header h) {
        jdbc.update("insert into score_tables(id, owner_org_id, exam_code, skill, scope, test_id, function) values (?,?,?,?,?,?,?)",
                h.id(), h.ownerOrgId(), h.examCode(), h.skill(), h.scope().name(), h.testId(), h.function());
    }

    void insertVersion(UUID id, Header h, int versionNo, List<ScoreRow> rows, UUID by) {
        jdbc.update("insert into score_table_versions(id, score_table_id, owner_org_id, exam_code, version_no, entries, created_by) values (?,?,?,?,?,?::jsonb,?)",
                id, h.id(), h.ownerOrgId(), h.examCode(), versionNo, text(rows), by);
    }

    int updateDraft(UUID versionId, List<ScoreRow> rows) {
        return jdbc.update("update score_table_versions set entries = ?::jsonb, updated_at = now() where id = ? and status in ('DRAFT', 'CHANGES_REQUESTED')",
                text(rows), versionId);
    }
}
