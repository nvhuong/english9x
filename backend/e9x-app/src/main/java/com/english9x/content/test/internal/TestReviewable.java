package com.english9x.content.test.internal;

import com.english9x.content.question.api.QuestionGroupVersions;
import com.english9x.content.test.api.TestVersions;
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
 * The {@code test} content type of the review workflow, over {@code test_versions}. Runs in the workflow's transaction (same connection, same row-level
 * security context). Its dependencies are the GROUP VERSIONS of its parts, each once, in the order of the test: the workflow refuses to approve the test
 * while one of them is not PUBLISHED (and locks them FOR SHARE while it decides). The author is {@code created_by}, the one person who can edit and submit.
 */
@Component
class TestReviewable implements ReviewableType {

    private final JdbcTemplate jdbc;

    TestReviewable(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    @Override
    public String type() {
        return TestVersions.REVIEW_TYPE;
    }

    @Override
    public Optional<Reviewable> lockForReview(UUID versionId) {
        return jdbc.query("select id, owner_org_id, exam_code, created_by, status from test_versions where id = ? for update",
                (rs, n) -> {
                    UUID id = rs.getObject("id", UUID.class);
                    List<VersionRef> groups = jdbc.query("select group_version_id from test_part_groups where test_version_id = ? order by part_position, position",
                            (r, i) -> new VersionRef(QuestionGroupVersions.REVIEW_TYPE, r.getObject(1, UUID.class)), id);
                    return (Reviewable) new Version(id, Optional.ofNullable(rs.getObject("owner_org_id", UUID.class)), rs.getString("exam_code"),
                            rs.getObject("created_by", UUID.class), ContentStatus.valueOf(rs.getString("status")), groups);
                }, versionId).stream().findFirst();
    }

    @Override
    public Optional<ContentStatus> lockStatus(UUID versionId) {
        return jdbc.query("select status from test_versions where id = ? for share", (rs, n) -> ContentStatus.valueOf(rs.getString(1)), versionId)
                .stream().findFirst();
    }

    @Override
    public Optional<VersionStatus> readStatus(UUID versionId) {
        return jdbc.query("select status, owner_org_id from test_versions where id = ?",
                (rs, n) -> new VersionStatus(ContentStatus.valueOf(rs.getString(1)), Optional.ofNullable(rs.getObject(2, UUID.class))), versionId)
                .stream().findFirst();
    }

    @Override
    public void setStatus(UUID versionId, ContentStatus status) {
        jdbc.update("update test_versions set status = ?, updated_at = now() where id = ?", status.name(), versionId);
    }

    record Version(UUID versionId, Optional<UUID> ownerOrgId, String examCode, UUID authorId, ContentStatus status, List<VersionRef> dependencies)
            implements Reviewable {
        @Override
        public String type() {
            return TestVersions.REVIEW_TYPE;
        }
    }
}
