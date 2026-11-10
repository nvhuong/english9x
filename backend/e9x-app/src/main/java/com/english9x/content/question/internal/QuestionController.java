package com.english9x.content.question.internal;

import com.english9x.content.question.api.QuestionVersionView;
import com.english9x.workflow.api.ContentStatus;
import com.fasterxml.jackson.databind.JsonNode;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.media.Schema;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.springframework.http.CacheControl;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * The CMS question API (F09, F18). The author is the signed-in user (nothing about who comes from the body); the owner is the bound context. Every answer
 * that carries a question carries its answer key: these routes are for the AUTHOR and REVIEWER of the exam only, and answer {@code Cache-Control: no-store}.
 * The learner never reaches this controller: the exam room reads {@code QuestionVersions#examView}.
 */
@RestController
@RequestMapping("/api/cms/questions")
class QuestionController {

    @Schema(name = "QuestionRequest")
    record QuestionRequest(
            @Schema(description = "the question type; fixed for the life of the question", example = "TOEIC_P5_INCOMPLETE_SENTENCE") String typeCode,
            @Schema(description = "the group the question is written for (optional; fixed when the question is made)", nullable = true) UUID groupId,
            @Schema(type = "object", description = "what the learner sees; checked against the content schema of the type") JsonNode content,
            @Schema(type = "object", description = "the solution side: answers, transcript, sample answer; checked against the answer-key schema of the type") JsonNode answerKey,
            @Schema(description = "shown after submitting", nullable = true) String explanation,
            @Schema(description = "default 1", nullable = true) BigDecimal points) {}

    @Schema(name = "QuestionVersion")
    record QuestionVersionDto(UUID versionId, UUID questionId, int versionNo, ContentStatus status, @Schema(nullable = true) UUID ownerOrgId, String examCode,
                              String typeCode, @Schema(type = "object") JsonNode content, @Schema(type = "object") JsonNode answerKey,
                              @Schema(nullable = true) String explanation, BigDecimal points, UUID createdBy, Instant createdAt, Instant updatedAt) {
        static QuestionVersionDto of(QuestionVersionView v) {
            return new QuestionVersionDto(v.versionId(), v.questionId(), v.versionNo(), v.status(), v.ownerOrgId().orElse(null), v.examCode(), v.typeCode(),
                    v.content(), v.answerKey(), v.explanation(), v.points(), v.createdBy(), v.createdAt(), v.updatedAt());
        }
    }

    @Schema(name = "QuestionSummary")
    record QuestionSummaryDto(UUID questionId, UUID versionId, int versionNo, ContentStatus status, String examCode, String typeCode,
                              @Schema(nullable = true) String title, BigDecimal points, UUID createdBy, Instant updatedAt) {}

    @Schema(name = "QuestionPage")
    record QuestionPageDto(List<QuestionSummaryDto> items, long total, int offset, int limit) {}

    @Schema(name = "SubmittedForReview")
    record SubmittedDto(UUID reviewId, UUID versionId) {}

    private final QuestionService service;

    QuestionController(QuestionService service) {
        this.service = service;
    }

    @PostMapping
    @Operation(summary = "Create a question (version 1, a draft) of a question type",
            description = "422 with errors[] when content or answerKey do not match the schemas of the type; each error's field is a JSON path such as content.options[2].key.")
    ResponseEntity<QuestionVersionDto> create(@RequestBody QuestionRequest body) {
        QuestionVersionView v = service.create(toBody(body));
        return ResponseEntity.status(HttpStatus.CREATED).cacheControl(CacheControl.noStore()).body(QuestionVersionDto.of(v));
    }

    @PutMapping("/{id}")
    @Operation(summary = "Edit a question: the open draft in place (200), or a new draft version when the latest is PUBLISHED (201)")
    ResponseEntity<QuestionVersionDto> update(@PathVariable UUID id, @RequestBody QuestionRequest body) {
        QuestionService.Saved saved = service.update(id, toBody(body));
        return ResponseEntity.status(saved.newVersion() ? HttpStatus.CREATED : HttpStatus.OK).cacheControl(CacheControl.noStore())
                .body(QuestionVersionDto.of(saved.view()));
    }

    @GetMapping
    @Operation(summary = "List questions (the latest version of each) the caller may read, newest change first")
    ResponseEntity<QuestionPageDto> list(@RequestParam(required = false) String exam, @RequestParam(required = false) String type,
                                         @RequestParam(required = false) ContentStatus status, @RequestParam(required = false) UUID groupId,
                                         @RequestParam(required = false) Integer page, @RequestParam(required = false) Integer size) {
        QuestionService.Page p = service.list(exam, type, status, groupId, page, size);
        List<QuestionSummaryDto> items = p.items().stream().map(s -> new QuestionSummaryDto(s.questionId(), s.versionId(), s.versionNo(), s.status(),
                s.examCode(), s.typeCode(), s.title(), s.points(), s.createdBy(), s.updatedAt())).toList();
        return ResponseEntity.ok().cacheControl(CacheControl.noStore()).body(new QuestionPageDto(items, p.total(), p.offset(), p.limit()));
    }

    @GetMapping("/{id}")
    @Operation(summary = "One question: its latest version, or the numbered one, with the answer key")
    ResponseEntity<QuestionVersionDto> get(@PathVariable UUID id, @RequestParam(required = false) Integer version) {
        return ResponseEntity.ok().cacheControl(CacheControl.noStore()).body(QuestionVersionDto.of(service.get(id, version)));
    }

    @GetMapping("/{id}/versions")
    @Operation(summary = "Every version of a question, oldest first, with the answer key (for comparing versions)")
    ResponseEntity<List<QuestionVersionDto>> versions(@PathVariable UUID id) {
        return ResponseEntity.ok().cacheControl(CacheControl.noStore()).body(service.versions(id).stream().map(QuestionVersionDto::of).toList());
    }

    @PostMapping("/{id}/submit")
    @Operation(summary = "Submit the open draft for review (the workflow records the author, audits and notifies the reviewers)")
    ResponseEntity<SubmittedDto> submit(@PathVariable UUID id) {
        UUID reviewId = service.submit(id);
        return ResponseEntity.status(HttpStatus.ACCEPTED).cacheControl(CacheControl.noStore()).body(new SubmittedDto(reviewId, null));
    }

    private static QuestionService.Body toBody(QuestionRequest r) {
        return new QuestionService.Body(r.typeCode(), r.groupId(), r.content(), r.answerKey(), r.explanation(), r.points());
    }
}
