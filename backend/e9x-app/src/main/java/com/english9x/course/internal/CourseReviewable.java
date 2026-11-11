package com.english9x.course.internal;

import com.english9x.content.strategy.api.StrategyLessonVersions;
import com.english9x.content.test.api.TestVersions;
import com.english9x.course.api.CourseVersions;
import com.english9x.workflow.api.ContentStatus;
import com.english9x.workflow.api.Reviewable;
import com.english9x.workflow.api.ReviewableType;
import com.english9x.workflow.api.VersionRef;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

/**
 * The {@code course} content type of the review workflow, over {@code course_versions}. Its dependencies are every strategy lesson version and test version its
 * lessons name, each once, in course order: the workflow refuses to approve the course while one of them is not PUBLISHED.
 */
@Component
class CourseReviewable implements ReviewableType {

    private final JdbcTemplate jdbc;

    CourseReviewable(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    @Override
    public String type() {
        return CourseVersions.REVIEW_TYPE;
    }

    @Override
    public Optional<Reviewable> lockForReview(UUID versionId) {
        return jdbc.query("select id, owner_org_id, exam_code, created_by, status from course_versions where id = ? for update", (rs, n) -> {
            UUID id = rs.getObject("id", UUID.class);
            Set<VersionRef> deps = new LinkedHashSet<>();
            jdbc.query("select kind, ref_id from course_lessons where course_version_id = ? order by chapter_position, position", r -> {
                deps.add(new VersionRef("LESSON".equals(r.getString(1)) ? StrategyLessonVersions.REVIEW_TYPE : TestVersions.REVIEW_TYPE, r.getObject(2, UUID.class)));
            }, id);
            return (Reviewable) new Version(id, Optional.ofNullable(rs.getObject("owner_org_id", UUID.class)), rs.getString("exam_code"),
                    rs.getObject("created_by", UUID.class), ContentStatus.valueOf(rs.getString("status")), List.copyOf(deps));
        }, versionId).stream().findFirst();
    }

    @Override
    public Optional<ContentStatus> lockStatus(UUID versionId) {
        return jdbc.query("select status from course_versions where id = ? for share", (rs, n) -> ContentStatus.valueOf(rs.getString(1)), versionId)
                .stream().findFirst();
    }

    @Override
    public Optional<VersionStatus> readStatus(UUID versionId) {
        return jdbc.query("select status, owner_org_id from course_versions where id = ?",
                (rs, n) -> new VersionStatus(ContentStatus.valueOf(rs.getString(1)), Optional.ofNullable(rs.getObject(2, UUID.class))), versionId)
                .stream().findFirst();
    }

    @Override
    public void setStatus(UUID versionId, ContentStatus status) {
        jdbc.update("update course_versions set status = ?, updated_at = now() where id = ?", status.name(), versionId);
    }

    record Version(UUID versionId, Optional<UUID> ownerOrgId, String examCode, UUID authorId, ContentStatus status, List<VersionRef> dependencies)
            implements Reviewable {
        @Override
        public String type() {
            return CourseVersions.REVIEW_TYPE;
        }
    }
}
