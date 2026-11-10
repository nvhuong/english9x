package com.english9x.content.strategy.internal;

import com.english9x.content.question.api.QuestionVersions;
import com.english9x.content.strategy.api.StrategyLessonVersions;
import com.english9x.workflow.api.ContentStatus;
import com.english9x.workflow.api.Reviewable;
import com.english9x.workflow.api.ReviewableType;
import com.english9x.workflow.api.VersionRef;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

/**
 * The {@code strategy_lesson} content type of the review workflow, over {@code strategy_lesson_versions}. Runs in the workflow's transaction. The author
 * is {@code created_by}. The dependencies are the question versions named by the {@code embedded-question} blocks: the workflow refuses to publish the
 * lesson while one is not PUBLISHED (and locks it, or reads it through {@code e9x_question_version_status} when it is English9x content used from an
 * organisation).
 */
@Component
class StrategyReviewable implements ReviewableType {

    private final JdbcTemplate jdbc;
    private final ObjectMapper json;

    StrategyReviewable(JdbcTemplate jdbc, ObjectMapper json) {
        this.jdbc = jdbc;
        this.json = json;
    }

    @Override
    public String type() {
        return StrategyLessonVersions.REVIEW_TYPE;
    }

    @Override
    public Optional<Reviewable> lockForReview(UUID versionId) {
        return jdbc.query("select id, owner_org_id, exam_code, created_by, status, blocks::text from strategy_lesson_versions where id = ? for update",
                (rs, n) -> {
                    try {
                        return (Reviewable) new Version(rs.getObject("id", UUID.class), Optional.ofNullable(rs.getObject("owner_org_id", UUID.class)),
                                rs.getString("exam_code"), rs.getObject("created_by", UUID.class), ContentStatus.valueOf(rs.getString("status")),
                                embeddedQuestions(json.readTree(rs.getString(6))));
                    } catch (com.fasterxml.jackson.core.JsonProcessingException e) {
                        throw new java.sql.SQLException("blocks are not JSON", e);
                    }
                }, versionId).stream().findFirst();
    }

    /** The distinct question versions the blocks embed, in order of appearance. */
    static List<VersionRef> embeddedQuestions(JsonNode blocks) {
        Set<UUID> ids = new LinkedHashSet<>();
        for (JsonNode b : blocks) {
            if ("embedded-question".equals(b.path("type").asText()) && b.path("questionVersionId").isTextual()) {
                ids.add(UUID.fromString(b.path("questionVersionId").asText()));
            }
        }
        return ids.stream().map(id -> new VersionRef(QuestionVersions.REVIEW_TYPE, id)).toList();
    }

    @Override
    public Optional<ContentStatus> lockStatus(UUID versionId) {
        return jdbc.query("select status from strategy_lesson_versions where id = ? for share", (rs, n) -> ContentStatus.valueOf(rs.getString(1)), versionId)
                .stream().findFirst();
    }

    @Override
    public Optional<VersionStatus> readStatus(UUID versionId) {
        return jdbc.query("select status, owner_org_id from e9x_strategy_lesson_version_status(?)",
                (rs, n) -> new VersionStatus(ContentStatus.valueOf(rs.getString(1)), Optional.ofNullable(rs.getObject(2, UUID.class))), versionId)
                .stream().findFirst();
    }

    @Override
    public void setStatus(UUID versionId, ContentStatus status) {
        jdbc.update("update strategy_lesson_versions set status = ?, updated_at = now() where id = ?", status.name(), versionId);
    }

    record Version(UUID versionId, Optional<UUID> ownerOrgId, String examCode, UUID authorId, ContentStatus status, List<VersionRef> dependencies)
            implements Reviewable {
        @Override
        public String type() {
            return StrategyLessonVersions.REVIEW_TYPE;
        }
    }
}
