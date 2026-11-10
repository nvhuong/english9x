package com.english9x.content.test.internal;

import com.english9x.content.test.api.TestStaffView;
import com.english9x.workflow.api.ContentStatus;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

/** {@code tests}, {@code test_versions}, their parts and groups, in the caller's transaction: Row-Level Security answers for the bound user and organisation. */
@Repository
class TestRepository {

    record Header(UUID id, UUID ownerOrgId, String examCode, String variant, UUID createdBy) {}

    record PartRow(String sectionCode, int durationSec, List<UUID> groupVersionIds) {}

    record Summary(UUID testId, UUID versionId, int versionNo, ContentStatus status, String examCode, String variant, String title, int parts, UUID createdBy,
                   Instant updatedAt) {}

    /** A TEST-scope conversion table of the test (the override of one skill). */
    record Override(UUID scoreTableId, String skill) {}

    private static final String COLUMNS = "id, test_id, version_no, status, owner_org_id, exam_code, variant, title, max_resumes, created_by, created_at, updated_at";

    private final JdbcTemplate jdbc;

    TestRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    private static Instant instant(Timestamp t) {
        return t == null ? null : t.toInstant();
    }

    /** The version row with its parts. {@code servableOnly}: only PUBLISHED / ARCHIVED (the exam room never serves a draft, whoever asks). */
    private Optional<TestStaffView> one(String where, boolean servableOnly, Object... args) {
        return jdbc.query("select " + COLUMNS + " from test_versions where " + where + (servableOnly ? " and status in ('PUBLISHED', 'ARCHIVED')" : "") + " limit 1",
                (rs, n) -> staff(rs), args).stream().findFirst();
    }

    private TestStaffView staff(ResultSet rs) throws SQLException {
        UUID id = rs.getObject("id", UUID.class);
        Integer maxResumes = rs.getObject("max_resumes") == null ? null : rs.getInt("max_resumes");
        return new TestStaffView(id, rs.getObject("test_id", UUID.class), rs.getInt("version_no"), ContentStatus.valueOf(rs.getString("status")),
                Optional.ofNullable(rs.getObject("owner_org_id", UUID.class)), rs.getString("exam_code"), rs.getString("variant"), rs.getString("title"),
                maxResumes, parts(id), rs.getObject("created_by", UUID.class), instant(rs.getTimestamp("created_at")), instant(rs.getTimestamp("updated_at")));
    }

    private List<TestStaffView.Part> parts(UUID versionId) {
        record Row(int position, String sectionCode, int durationSec, UUID group) {}
        List<Row> rows = jdbc.query("""
                select p.position, p.section_code, p.duration_sec, g.group_version_id
                  from test_parts p left join test_part_groups g on g.test_version_id = p.test_version_id and g.part_position = p.position
                 where p.test_version_id = ? order by p.position, g.position
                """, (rs, n) -> new Row(rs.getInt(1), rs.getString(2), rs.getInt(3), rs.getObject(4, UUID.class)), versionId);
        List<TestStaffView.Part> out = new ArrayList<>();
        for (Row r : rows) {
            if (out.isEmpty() || out.get(out.size() - 1).position() != r.position()) {
                out.add(new TestStaffView.Part(r.position(), r.sectionCode(), r.durationSec(), new ArrayList<>()));
            }
            if (r.group() != null) {
                out.get(out.size() - 1).groupVersionIds().add(r.group());
            }
        }
        return out;
    }

    Optional<Header> header(UUID testId) {
        return jdbc.query("select id, owner_org_id, exam_code, variant, created_by from tests where id = ?",
                (rs, n) -> new Header(rs.getObject(1, UUID.class), rs.getObject(2, UUID.class), rs.getString(3), rs.getString(4), rs.getObject(5, UUID.class)), testId)
                .stream().findFirst();
    }

    Optional<TestStaffView> latest(UUID testId) {
        return jdbc.query("select " + COLUMNS + " from test_versions where test_id = ? order by version_no desc limit 1", (rs, n) -> staff(rs), testId)
                .stream().findFirst();
    }

    Optional<TestStaffView> versionNo(UUID testId, int versionNo) {
        return one("test_id = ? and version_no = ?", false, testId, versionNo);
    }

    List<TestStaffView> versions(UUID testId) {
        return jdbc.query("select " + COLUMNS + " from test_versions where test_id = ? order by version_no", (rs, n) -> staff(rs), testId);
    }

    Optional<TestStaffView> find(UUID versionId) {
        return one("id = ?", false, versionId);
    }

    Optional<TestStaffView> findServable(UUID versionId) {
        return one("id = ?", true, versionId);
    }

    Optional<UUID> testOf(UUID versionId) {
        return jdbc.query("select test_id from test_versions where id = ?", (rs, n) -> rs.getObject(1, UUID.class), versionId).stream().findFirst();
    }

    List<Summary> list(String examCode, ContentStatus status, int limit, int offset) {
        return jdbc.query("select * from (select distinct on (v.test_id) v.id, v.test_id, v.version_no, v.status, v.exam_code, v.variant, v.title, "
                + "(select count(*) from test_parts p where p.test_version_id = v.id) as parts, v.created_by, v.updated_at "
                + "from test_versions v " + (examCode == null ? "" : "where v.exam_code = ? ") + "order by v.test_id, v.version_no desc) latest "
                + (status == null ? "" : "where status = '" + status.name() + "' ") + "order by updated_at desc, id limit ? offset ?",
                (rs, n) -> new Summary(rs.getObject("test_id", UUID.class), rs.getObject("id", UUID.class), rs.getInt("version_no"),
                        ContentStatus.valueOf(rs.getString("status")), rs.getString("exam_code"), rs.getString("variant"), rs.getString("title"), rs.getInt("parts"),
                        rs.getObject("created_by", UUID.class), instant(rs.getTimestamp("updated_at"))),
                examCode == null ? new Object[] {limit, offset} : new Object[] {examCode, limit, offset});
    }

    /** The conversion tables of TEST scope of this test (set by the administrator of its owner); a row per skill. */
    List<Override> overrides(UUID testId) {
        return jdbc.query("select id, skill from score_tables where scope = 'TEST' and test_id = ? order by skill",
                (rs, n) -> new Override(rs.getObject(1, UUID.class), rs.getString(2)), testId);
    }

    // ---- writes -------------------------------------------------------------------------------------------------------------------------

    void insertHeader(Header h) {
        jdbc.update("insert into tests(id, owner_org_id, exam_code, variant, created_by) values (?,?,?,?,?)", h.id(), h.ownerOrgId(), h.examCode(), h.variant(), h.createdBy());
    }

    void insertVersion(UUID id, Header h, int versionNo, String title, Integer maxResumes, List<PartRow> parts, UUID by) {
        jdbc.update("insert into test_versions(id, test_id, owner_org_id, exam_code, variant, version_no, title, max_resumes, created_by) values (?,?,?,?,?,?,?,?,?)",
                id, h.id(), h.ownerOrgId(), h.examCode(), h.variant(), versionNo, title, maxResumes, by);
        insertParts(id, h, parts);
    }

    private void insertParts(UUID versionId, Header h, List<PartRow> parts) {
        for (int i = 0; i < parts.size(); i++) {
            PartRow p = parts.get(i);
            jdbc.update("insert into test_parts(test_version_id, position, section_code, duration_sec, owner_org_id, exam_code) values (?,?,?,?,?,?)",
                    versionId, i + 1, p.sectionCode(), p.durationSec(), h.ownerOrgId(), h.examCode());
            for (int j = 0; j < p.groupVersionIds().size(); j++) {
                jdbc.update("insert into test_part_groups(test_version_id, part_position, position, group_version_id, owner_org_id, exam_code) values (?,?,?,?,?,?)",
                        versionId, i + 1, j + 1, p.groupVersionIds().get(j), h.ownerOrgId(), h.examCode());
            }
        }
    }

    /** Rewrites an editable version: 0 when it is not (or no longer) a DRAFT / CHANGES_REQUESTED. The parts are replaced (the child guards allow it only then). */
    int updateDraft(UUID versionId, Header h, String title, Integer maxResumes, List<PartRow> parts) {
        int rows = jdbc.update("update test_versions set title = ?, max_resumes = ?, updated_at = now() where id = ? and status in ('DRAFT', 'CHANGES_REQUESTED')",
                title, maxResumes, versionId);
        if (rows == 1) {
            jdbc.update("delete from test_part_groups where test_version_id = ?", versionId);
            jdbc.update("delete from test_parts where test_version_id = ?", versionId);
            insertParts(versionId, h, parts);
        }
        return rows;
    }
}
