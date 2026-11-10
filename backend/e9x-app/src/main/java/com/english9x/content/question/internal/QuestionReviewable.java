package com.english9x.content.question.internal;

import com.english9x.content.question.api.QuestionVersions;
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
 * The {@code question} content type of the review workflow, over {@code question_versions}. Runs in the workflow's transaction (same connection, same
 * row-level security context). The author the workflow records is {@code created_by}: the only person who can edit or submit the version (policy),
 * so the self-review trigger of {@code review_requests} compares the decider with the one person who contributed. A question depends on no other version
 * (the files it points at are not versioned).
 *
 * <p>The locks are the contract of {@link ReviewableType}: the REVIEWER policy of the table lets a reviewer of the exam lock the row (UPDATE policy);
 * {@link #readStatus} goes through a SECURITY DEFINER function so that a dependency on published English9x content never opens the row (and its
 * answer key) to the reader.
 */
@Component
class QuestionReviewable implements ReviewableType {

    private final JdbcTemplate jdbc;

    QuestionReviewable(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    @Override
    public String type() {
        return QuestionVersions.REVIEW_TYPE;
    }

    @Override
    public Optional<Reviewable> lockForReview(UUID versionId) {
        return jdbc.query("select id, owner_org_id, exam_code, created_by, status from question_versions where id = ? for update",
                (rs, n) -> (Reviewable) new Version(rs.getObject("id", UUID.class), Optional.ofNullable(rs.getObject("owner_org_id", UUID.class)),
                        rs.getString("exam_code"), rs.getObject("created_by", UUID.class), ContentStatus.valueOf(rs.getString("status"))),
                versionId).stream().findFirst();
    }

    @Override
    public Optional<ContentStatus> lockStatus(UUID versionId) {
        return jdbc.query("select status from question_versions where id = ? for share", (rs, n) -> ContentStatus.valueOf(rs.getString(1)), versionId)
                .stream().findFirst();
    }

    @Override
    public Optional<VersionStatus> readStatus(UUID versionId) {
        return jdbc.query("select status, owner_org_id from e9x_question_version_status(?)",
                (rs, n) -> new VersionStatus(ContentStatus.valueOf(rs.getString(1)), Optional.ofNullable(rs.getObject(2, UUID.class))), versionId)
                .stream().findFirst();
    }

    @Override
    public void setStatus(UUID versionId, ContentStatus status) {
        jdbc.update("update question_versions set status = ?, updated_at = now() where id = ?", status.name(), versionId);
    }

    record Version(UUID versionId, Optional<UUID> ownerOrgId, String examCode, UUID authorId, ContentStatus status) implements Reviewable {
        @Override
        public String type() {
            return QuestionVersions.REVIEW_TYPE;
        }

        @Override
        public List<VersionRef> dependencies() {
            return List.of();
        }
    }
}
