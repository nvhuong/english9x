package com.english9x.content.review;

import com.english9x.workflow.api.ReviewService;
import com.english9x.workflow.api.ReviewView;
import com.fasterxml.jackson.databind.JsonNode;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.media.Schema;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.springframework.http.CacheControl;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;

/**
 * F17/F18, the review screens' API. Decisions go through {@link ReviewService}, which checks the role, the self-review rule and the dependencies and
 * writes the {@code review.*} audit records itself: this controller must NOT audit them again. Failures of the workflow are problem+json already.
 */
@RestController
@RequestMapping("/api/cms/reviews")
class ReviewController {

    @Schema(name = "ReviewQueueLine")
    record QueueDto(@Schema(nullable = true) UUID reviewId, @Schema(nullable = true) UUID bundleId, int size, String type, String examCode,
                    @Schema(nullable = true) UUID ownerOrgId, UUID submittedBy, Instant submittedAt, @Schema(nullable = true) String authorName) {}

    @Schema(name = "ReviewMember")
    record MemberDto(ReviewDto review, @Schema(nullable = true) String authorName, boolean ownVersion) {}

    @Schema(name = "ReviewRecord")
    record ReviewDto(UUID id, @Schema(nullable = true) UUID bundleId, String type, UUID versionId, @Schema(nullable = true) UUID ownerOrgId, String examCode,
                     UUID authorId, UUID submittedBy, Instant submittedAt, String status, @Schema(nullable = true) UUID reviewerId,
                     @Schema(nullable = true) Instant decidedAt, @Schema(nullable = true) String comment) {
        static ReviewDto of(ReviewView r) {
            return new ReviewDto(r.id(), r.bundleId().orElse(null), r.type(), r.versionId(), r.ownerOrgId().orElse(null), r.examCode(), r.authorId(),
                    r.submittedBy(), r.submittedAt(), r.status().name(), r.reviewerId().orElse(null), r.decidedAt().orElse(null), r.comment().orElse(null));
        }
    }

    @Schema(name = "ReviewSnapshot")
    record SnapshotDto(UUID versionId, int versionNo, String status, @Schema(implementation = Object.class, description = "the content fields of the version, without identity or bookkeeping") JsonNode content) {}

    @Schema(name = "ReviewDetail")
    record DetailDto(ReviewDto review, @Schema(nullable = true) String authorName,
                     @Schema(description = "the caller wrote or submitted this version: the server refuses their decision") boolean ownVersion,
                     @Schema(nullable = true, description = "the version under review; absent when the caller may not read it") SnapshotDto version,
                     @Schema(nullable = true, description = "the latest earlier published or archived version of the same item, to compare with") SnapshotDto previous) {}

    @Schema(name = "ReviewComment")
    record CommentRequest(String comment) {}

    private final ReviewService reviews;
    private final ReviewDetails details;

    ReviewController(ReviewService reviews, ReviewDetails details) {
        this.reviews = reviews;
        this.details = details;
    }

    @GetMapping
    @Operation(summary = "The open review queue the caller may see, oldest first: one line per bundle and per standalone review")
    ResponseEntity<List<QueueDto>> queue(@RequestParam(defaultValue = "0") int offset, @RequestParam(defaultValue = "50") int limit) {
        List<QueueDto> out = details.queue(offset, limit).stream().map(l -> {
            var e = l.entry();
            return new QueueDto(e.reviewId().orElse(null), e.bundleId().orElse(null), e.size(), e.type(), e.examCode(), e.ownerOrgId().orElse(null),
                    e.submittedBy(), e.submittedAt(), l.authorName());
        }).toList();
        return ResponseEntity.ok().cacheControl(CacheControl.noStore()).body(out);
    }

    @GetMapping("/{reviewId}")
    @Operation(summary = "One review with the version under review and the earlier published version to compare it with")
    ResponseEntity<DetailDto> get(@PathVariable UUID reviewId) {
        ReviewDetails.Detail d = details.detail(reviewId).orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND));
        return ResponseEntity.ok().cacheControl(CacheControl.noStore())
                .body(new DetailDto(ReviewDto.of(d.review()), d.authorName(), d.ownVersion(), snap(d.version()), snap(d.previous())));
    }

    @GetMapping("/bundles/{bundleId}/members")
    @Operation(summary = "The reviews of a bundle the caller may see (the whole of an import, a course and its lessons)")
    ResponseEntity<List<MemberDto>> members(@PathVariable UUID bundleId, @RequestParam(defaultValue = "0") int offset,
                                            @RequestParam(defaultValue = "50") int limit) {
        List<ReviewDetails.MemberLine> lines = details.members(bundleId, offset, limit);
        if (lines.isEmpty() && offset == 0) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND);
        }
        return ResponseEntity.ok().cacheControl(CacheControl.noStore())
                .body(lines.stream().map(l -> new MemberDto(ReviewDto.of(l.review()), l.authorName(), l.ownVersion())).toList());
    }

    @PostMapping("/{reviewId}/approve")
    @Operation(summary = "Approve (publish) the version. 403 when the caller wrote or submitted it; 409 while a dependency is not published")
    ResponseEntity<Void> approve(@PathVariable UUID reviewId) {
        reviews.approve(reviewId);
        return ResponseEntity.noContent().cacheControl(CacheControl.noStore()).build();
    }

    @PostMapping("/{reviewId}/request-changes")
    @Operation(summary = "Send the version back to its author with a comment (required)")
    ResponseEntity<Void> requestChanges(@PathVariable UUID reviewId, @RequestBody CommentRequest body) {
        reviews.requestChanges(reviewId, body.comment());
        return ResponseEntity.noContent().cacheControl(CacheControl.noStore()).build();
    }

    @PostMapping("/bundles/{bundleId}/approve")
    @Operation(summary = "Approve every member of a bundle, all or none")
    ResponseEntity<Void> approveBundle(@PathVariable UUID bundleId) {
        reviews.approveBundle(bundleId);
        return ResponseEntity.noContent().cacheControl(CacheControl.noStore()).build();
    }

    @PostMapping("/bundles/{bundleId}/request-changes")
    @Operation(summary = "Send every member of a bundle back with a comment, all or none")
    ResponseEntity<Void> requestBundleChanges(@PathVariable UUID bundleId, @RequestBody CommentRequest body) {
        reviews.requestBundleChanges(bundleId, body.comment());
        return ResponseEntity.noContent().cacheControl(CacheControl.noStore()).build();
    }

    private static SnapshotDto snap(ReviewDetails.Snapshot s) {
        return s == null ? null : new SnapshotDto(s.versionId(), s.versionNo(), s.status(), s.content());
    }
}
