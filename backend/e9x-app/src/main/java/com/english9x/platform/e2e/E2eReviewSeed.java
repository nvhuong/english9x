package com.english9x.platform.e2e;

import com.english9x.org.api.CurrentContextHolder;
import com.english9x.workflow.api.ReviewService;
import com.english9x.workflow.api.VersionRef;
import java.util.Map;
import java.util.UUID;
import org.springframework.context.annotation.Profile;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;

/**
 * Stands in for the CMS review screens (P2.9) where the scenario needs a REAL review: {@code POST /api/test/seed/review-flow} creates
 * an "e2e_doc" version and then, as the named people, runs the real {@link ReviewService}: the author submits it and the reviewer
 * approves it or sends it back. Unlike {@code /review-event} nothing is faked after the version exists: Authz, RLS, the lifecycle trigger,
 * the events, the notifications and the audit log all run as they will for real content. Only the content row itself is inserted
 * directly (as the table owner), because no content module exists yet. Exists only under the profile {@code e2e}.
 */
@RestController
@Profile("e2e")
@RequestMapping("/api/test/seed")
class E2eReviewSeed {

    private final SeedDatabase db;
    private final ReviewService reviews;

    E2eReviewSeed(SeedDatabase db, ReviewService reviews) {
        this.db = db;
        this.reviews = reviews;
    }

    /** @param outcome {@code submitted} (stop after the submit), {@code approved} or {@code changes} */
    record ReviewFlowRequest(UUID authorId, UUID reviewerId, UUID orgId, String examCode, String outcome) {}

    @PostMapping("/review-flow")
    @ResponseStatus(HttpStatus.CREATED)
    Map<String, Object> reviewFlow(@RequestBody ReviewFlowRequest req) {
        String outcome = req.outcome() == null ? "approved" : req.outcome();
        if (!java.util.List.of("submitted", "approved", "changes").contains(outcome) || req.authorId() == null
                || (!"submitted".equals(outcome) && req.reviewerId() == null)) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "authorId; reviewerId unless outcome=submitted; outcome: submitted | approved | changes");
        }
        String exam = req.examCode() != null ? req.examCode() : "IELTS";
        db.requireCatalogExam(exam);
        db.jdbc().execute("create table if not exists e2e_docs (id uuid primary key, owner_org_id uuid, exam_code text not null,"
                + " author_id uuid not null, status text not null default 'DRAFT')");
        db.jdbc().execute("create or replace trigger e2e_docs_lifecycle before update or delete on e2e_docs for each row execute function e9x_guard_version_lifecycle()");
        db.jdbc().execute("grant select, insert, update, delete on e2e_docs to e9x_app");
        UUID versionId = UUID.randomUUID();
        db.jdbc().update("insert into e2e_docs(id, owner_org_id, exam_code, author_id) values (?, ?, ?, ?)", versionId, req.orgId(), exam, req.authorId());

        UUID reviewId;
        try (CurrentContextHolder.Scope ignored = CurrentContextHolder.bind(req.authorId(), req.orgId())) {
            reviewId = reviews.submit(new VersionRef(E2eDocs.TYPE, versionId));
        }
        if ("approved".equals(outcome)) {
            try (CurrentContextHolder.Scope ignored = CurrentContextHolder.bind(req.reviewerId(), req.orgId())) {
                reviews.approve(reviewId);
            }
        } else if ("changes".equals(outcome)) {
            try (CurrentContextHolder.Scope ignored = CurrentContextHolder.bind(req.reviewerId(), req.orgId())) {
                reviews.requestChanges(reviewId, "e2e feedback");
            }
        }
        return Map.of("reviewId", reviewId.toString(), "versionId", versionId.toString(), "outcome", outcome);
    }
}
