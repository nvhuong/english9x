package com.english9x.content.question.internal;

import com.english9x.content.question.api.QuestionGroupVersions;
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
 * The {@code question_group} content type of the review workflow. Its dependencies are its member question versions, in order: the workflow refuses to
 * approve the group while one of them is not PUBLISHED (and locks them FOR SHARE while it decides). Authors and locks as {@link QuestionReviewable}.
 */
@Component
class GroupReviewable implements ReviewableType {

    private final JdbcTemplate jdbc;

    GroupReviewable(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    @Override
    public String type() {
        return QuestionGroupVersions.REVIEW_TYPE;
    }

    @Override
    public Optional<Reviewable> lockForReview(UUID versionId) {
        return jdbc.query("select id, owner_org_id, exam_code, created_by, status from question_group_versions where id = ? for update",
                (rs, n) -> {
                    UUID id = rs.getObject("id", UUID.class);
                    List<VersionRef> members = jdbc.query("select question_version_id from question_group_items where group_version_id = ? order by position",
                            (r, i) -> new VersionRef(QuestionVersions.REVIEW_TYPE, r.getObject(1, UUID.class)), id);
                    return (Reviewable) new Version(id, Optional.ofNullable(rs.getObject("owner_org_id", UUID.class)), rs.getString("exam_code"),
                            rs.getObject("created_by", UUID.class), ContentStatus.valueOf(rs.getString("status")), members);
                }, versionId).stream().findFirst();
    }

    @Override
    public Optional<ContentStatus> lockStatus(UUID versionId) {
        return jdbc.query("select status from question_group_versions where id = ? for share", (rs, n) -> ContentStatus.valueOf(rs.getString(1)), versionId)
                .stream().findFirst();
    }

    @Override
    public Optional<VersionStatus> readStatus(UUID versionId) {
        return jdbc.query("select status, owner_org_id from e9x_question_group_version_status(?)",
                (rs, n) -> new VersionStatus(ContentStatus.valueOf(rs.getString(1)), Optional.ofNullable(rs.getObject(2, UUID.class))), versionId)
                .stream().findFirst();
    }

    @Override
    public void setStatus(UUID versionId, ContentStatus status) {
        jdbc.update("update question_group_versions set status = ?, updated_at = now() where id = ?", status.name(), versionId);
    }

    record Version(UUID versionId, Optional<UUID> ownerOrgId, String examCode, UUID authorId, ContentStatus status, List<VersionRef> dependencies)
            implements Reviewable {
        @Override
        public String type() {
            return QuestionGroupVersions.REVIEW_TYPE;
        }
    }
}
