package com.english9x.workflow.internal;

import com.english9x.workflow.api.ContentStatus;
import java.sql.Timestamp;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.stereotype.Repository;

/**
 * SQL of {@code review_requests} / {@code review_bundles}. Every statement runs in the caller's transaction, so Row-Level
 * Security answers for the bound user and organisation: a row outside the caller's context is simply not there.
 */
@Repository
class ReviewRepository {

    record Row(UUID id, UUID bundleId, String type, UUID versionId, UUID ownerOrgId, String examCode, UUID authorId,
               UUID submittedBy, java.time.Instant submittedAt, ContentStatus status, UUID reviewerId,
               java.time.Instant decidedAt, String comment) {}

    private static final String COLUMNS = "id, bundle_id, content_type, version_id, owner_org_id, exam_code, author_id, "
            + "submitted_by, submitted_at, status, reviewer_id, decided_at, comment";

    private static final RowMapper<Row> MAPPER = (rs, n) -> new Row(
            rs.getObject("id", UUID.class), rs.getObject("bundle_id", UUID.class), rs.getString("content_type"),
            rs.getObject("version_id", UUID.class), rs.getObject("owner_org_id", UUID.class), rs.getString("exam_code"),
            rs.getObject("author_id", UUID.class), rs.getObject("submitted_by", UUID.class),
            rs.getTimestamp("submitted_at").toInstant(), ContentStatus.valueOf(rs.getString("status")),
            rs.getObject("reviewer_id", UUID.class), instant(rs.getTimestamp("decided_at")), rs.getString("comment"));

    private final JdbcTemplate jdbc;

    ReviewRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    private static java.time.Instant instant(Timestamp t) {
        return t == null ? null : t.toInstant();
    }

    void insertBundle(UUID id, UUID ownerOrgId, UUID submittedBy, int memberCount) {
        jdbc.update("insert into review_bundles(id, owner_org_id, submitted_by, member_count) values (?,?,?,?)",
                id, ownerOrgId, submittedBy, memberCount);
    }

    void insertReview(UUID id, UUID bundleId, String type, UUID versionId, UUID ownerOrgId, String examCode, UUID authorId,
                      UUID submittedBy) {
        jdbc.update("insert into review_requests(id, bundle_id, content_type, version_id, owner_org_id, exam_code, author_id, submitted_by) "
                + "values (?,?,?,?,?,?,?,?)", id, bundleId, type, versionId, ownerOrgId, examCode, authorId, submittedBy);
    }

    /** As the caller may see it; no lock. */
    Optional<Row> find(UUID id) {
        return jdbc.query("select " + COLUMNS + " from review_requests where id = ?", MAPPER, id).stream().findFirst();
    }

    /** Locks the row until the transaction ends. Only a REVIEWER of the row's exam in its context gets it back. */
    Optional<Row> lock(UUID id) {
        return jdbc.query("select " + COLUMNS + " from review_requests where id = ? for update", MAPPER, id).stream().findFirst();
    }

    List<Row> members(UUID bundleId) {
        return jdbc.query("select " + COLUMNS + " from review_requests where bundle_id = ? order by id", MAPPER, bundleId);
    }

    /** In id order, so two transactions on one bundle take the locks in the same order. */
    List<Row> lockMembers(UUID bundleId) {
        return jdbc.query("select " + COLUMNS + " from review_requests where bundle_id = ? order by id for update", MAPPER, bundleId);
    }

    Optional<Integer> bundleSize(UUID bundleId) {
        return jdbc.query("select member_count from review_bundles where id = ?", (rs, n) -> rs.getInt(1), bundleId).stream().findFirst();
    }

    List<Row> open(int limit) {
        return jdbc.query("select " + COLUMNS + " from review_requests where status = 'IN_REVIEW' order by submitted_at, id limit ?", MAPPER, limit);
    }

    /** @return false when the review is no longer open (somebody decided it first) */
    boolean decide(UUID id, ContentStatus status, UUID reviewer, String comment) {
        return jdbc.update("update review_requests set status = ?, reviewer_id = ?, decided_at = now(), comment = ? "
                + "where id = ? and status = 'IN_REVIEW'", status.name(), reviewer, comment, id) == 1;
    }
}
