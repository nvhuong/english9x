package com.english9x.content.scoring.internal;

import com.english9x.content.scoring.api.ScoreTableVersions;
import com.english9x.workflow.api.ContentStatus;
import com.english9x.workflow.api.Reviewable;
import com.english9x.workflow.api.ReviewableType;
import com.english9x.workflow.api.VersionRef;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

/**
 * The {@code score_table} content type of the review workflow, over {@code score_table_versions}. Runs in the workflow's transaction. The author is
 * {@code created_by}. A table depends on nothing: the rows are all it holds.
 */
@Component
class ScoreTableReviewable implements ReviewableType {

    private final JdbcTemplate jdbc;

    ScoreTableReviewable(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    @Override
    public String type() {
        return ScoreTableVersions.REVIEW_TYPE;
    }

    @Override
    public Optional<Reviewable> lockForReview(UUID versionId) {
        return jdbc.query("select id, owner_org_id, exam_code, created_by, status from score_table_versions where id = ? for update",
                (rs, n) -> (Reviewable) new Version(rs.getObject("id", UUID.class), Optional.ofNullable(rs.getObject("owner_org_id", UUID.class)),
                        rs.getString("exam_code"), rs.getObject("created_by", UUID.class), ContentStatus.valueOf(rs.getString("status")), List.of()), versionId)
                .stream().findFirst();
    }

    @Override
    public Optional<ContentStatus> lockStatus(UUID versionId) {
        return jdbc.query("select status from score_table_versions where id = ? for share", (rs, n) -> ContentStatus.valueOf(rs.getString(1)), versionId)
                .stream().findFirst();
    }

    @Override
    public Optional<VersionStatus> readStatus(UUID versionId) {
        return jdbc.query("select status, owner_org_id from score_table_versions where id = ?",
                (rs, n) -> new VersionStatus(ContentStatus.valueOf(rs.getString(1)), Optional.ofNullable(rs.getObject(2, UUID.class))), versionId)
                .stream().findFirst();
    }

    @Override
    public void setStatus(UUID versionId, ContentStatus status) {
        jdbc.update("update score_table_versions set status = ?, updated_at = now() where id = ?", status.name(), versionId);
    }

    record Version(UUID versionId, Optional<UUID> ownerOrgId, String examCode, UUID authorId, ContentStatus status, List<VersionRef> dependencies)
            implements Reviewable {
        @Override
        public String type() {
            return ScoreTableVersions.REVIEW_TYPE;
        }
    }
}
