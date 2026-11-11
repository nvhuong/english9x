package com.english9x.content.review;

import com.english9x.content.question.api.QuestionGroupVersions;
import com.english9x.content.question.api.QuestionVersions;
import com.english9x.content.scoring.api.ScoreTableVersions;
import com.english9x.content.skill.api.GradingSkillVersions;
import com.english9x.content.strategy.api.StrategyLessonVersions;
import com.english9x.content.test.api.TestVersions;
import com.english9x.org.api.Authz;
import com.english9x.org.api.CurrentContextHolder;
import com.english9x.workflow.api.ReviewQueueEntry;
import com.english9x.workflow.api.ReviewService;
import com.english9x.workflow.api.ReviewView;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.function.Function;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import com.fasterxml.jackson.databind.JsonNode;

/**
 * The read side of the review screens: what ReviewService does not hand out (the author's name through the narrow definer, the content of the
 * version under review and of the one before it). Everything runs in the caller's transaction, so Row-Level Security answers for the caller:
 * a version the caller may not read is simply absent. Decisions are NOT here: they go through ReviewService, which audits them.
 */
@Service
class ReviewDetails {

    /** Where each content type keeps its versions: table, parent column. A fixed list (never request input), so the SQL below is safe. */
    private record Store(String table, String parentColumn) {}

    private static final Map<String, Store> STORES = Map.of(
            QuestionVersions.REVIEW_TYPE, new Store("question_versions", "question_id"),
            QuestionGroupVersions.REVIEW_TYPE, new Store("question_group_versions", "group_id"),
            StrategyLessonVersions.REVIEW_TYPE, new Store("strategy_lesson_versions", "lesson_id"),
            ScoreTableVersions.REVIEW_TYPE, new Store("score_table_versions", "score_table_id"),
            GradingSkillVersions.REVIEW_TYPE, new Store("grading_skill_versions", "grading_skill_id"),
            TestVersions.REVIEW_TYPE, new Store("test_versions", "test_id"));

    /** Identity and bookkeeping fields: they always differ between two versions and say nothing about the content. */
    private static final Set<String> NOISE = Set.of("versionId", "questionId", "groupId", "lessonId", "tableId", "skillId", "testId", "versionNo", "status",
            "ownerOrgId", "createdBy", "createdAt", "updatedAt");

    record Snapshot(UUID versionId, int versionNo, String status, JsonNode content) {}

    record Detail(ReviewView review, String authorName, boolean ownVersion, Snapshot version, Snapshot previous) {}

    private final ReviewService reviews;
    private final Authz authz;
    private final JdbcTemplate jdbc;
    private final ObjectMapper json;
    private final Map<String, Function<UUID, Optional<?>>> finders;

    ReviewDetails(ReviewService reviews, Authz authz, JdbcTemplate jdbc, ObjectMapper json, QuestionVersions questions, QuestionGroupVersions groups,
                  StrategyLessonVersions lessons, ScoreTableVersions scores, GradingSkillVersions skills, TestVersions tests) {
        this.reviews = reviews;
        this.authz = authz;
        this.jdbc = jdbc;
        this.json = json;
        this.finders = Map.of(QuestionVersions.REVIEW_TYPE, questions::find, QuestionGroupVersions.REVIEW_TYPE, groups::find,
                StrategyLessonVersions.REVIEW_TYPE, lessons::find, ScoreTableVersions.REVIEW_TYPE, scores::find,
                GradingSkillVersions.REVIEW_TYPE, skills::find, TestVersions.REVIEW_TYPE, tests::find);
    }

    record QueueLine(ReviewQueueEntry entry, String authorName) {}

    record MemberLine(ReviewView review, String authorName, boolean ownVersion) {}

    @Transactional(readOnly = true)
    List<QueueLine> queue(int offset, int limit) {
        return reviews.open(offset, limit).stream()
                .map(e -> new QueueLine(e, name(e.reviewId().or(e::bundleId).orElseThrow()))).toList();
    }

    @Transactional(readOnly = true)
    List<MemberLine> members(UUID bundleId, int offset, int limit) {
        Map<UUID, String> names = new HashMap<>();
        return reviews.members(bundleId, offset, limit).stream()
                .map(r -> new MemberLine(r, names.computeIfAbsent(r.authorId(), a -> name(r.id())), own(r))).toList();
    }

    @Transactional(readOnly = true)
    Optional<Detail> detail(UUID reviewId) {
        return reviews.find(reviewId).map(r -> {
            Snapshot current = snapshot(r.type(), r.versionId());
            Snapshot previous = previousOf(r).orElse(null);
            return new Detail(r, name(r.id()), own(r), current, previous);
        });
    }

    /** True when the caller wrote or submitted the version and may not decide on it (the server refuses the decision anyway). */
    private boolean own(ReviewView r) {
        UUID me = CurrentContextHolder.current().orElseThrow().userId();
        return !authz.canReviewOwnVersion() && (me.equals(r.authorId()) || me.equals(r.submittedBy()));
    }

    private String name(UUID reviewOrBundle) {
        return jdbc.queryForObject("select e9x_review_author_name(?)", String.class, reviewOrBundle);
    }

    private Optional<Snapshot> previousOf(ReviewView r) {
        Store s = STORES.get(r.type());
        if (s == null) {
            return Optional.empty();
        }
        List<UUID> ids = jdbc.queryForList("select p.id from " + s.table() + " p join " + s.table() + " c on c." + s.parentColumn() + " = p."
                + s.parentColumn() + " where c.id = ? and p.version_no < c.version_no and p.status in ('PUBLISHED','ARCHIVED') "
                + "order by p.version_no desc limit 1", UUID.class, r.versionId());
        return ids.stream().findFirst().map(id -> snapshot(r.type(), id));
    }

    private Snapshot snapshot(String type, UUID versionId) {
        Function<UUID, Optional<?>> finder = finders.get(type);
        Optional<?> view = finder == null ? Optional.empty() : finder.apply(versionId);
        if (view.isEmpty()) {
            return null; // the caller may not read this version (e.g. an ORG_ADMIN who is no reviewer): the review is still theirs to see
        }
        ObjectNode tree = json.valueToTree(view.get());
        int no = tree.path("versionNo").asInt();
        String status = tree.path("status").asText();
        NOISE.forEach(tree::remove);
        return new Snapshot(versionId, no, status, tree);
    }
}
