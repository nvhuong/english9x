package com.english9x.content.strategy.internal;

import com.english9x.content.strategy.api.StrategyLessonVersionView;
import com.english9x.workflow.api.ContentStatus;
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
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/** The CMS strategy-lesson API (F14). The author is the signed-in user; the owner is the bound context. For the AUTHOR and REVIEWER of the exam only. */
@RestController
@RequestMapping("/api/cms/strategy-lessons")
class StrategyController {

    @Schema(name = "StrategyLessonRequest")
    record LessonRequest(
            @Schema(description = "the exam of the lesson (needed to make a lesson; fixed afterwards)", example = "IELTS", nullable = true) String examCode,
            String title,
            @Schema(implementation = Object.class, description = "the ordered blocks: heading, paragraph, table, image, audio, video-upload, video-embed, callout, embedded-question, worked-example") JsonNode blocks,
            @Schema(description = "the question types the lesson teaches (at least one, of the exam)") List<String> questionTypeCodes) {}

    @Schema(name = "StrategyLessonVersion")
    record LessonDto(UUID versionId, UUID lessonId, int versionNo, ContentStatus status, @Schema(nullable = true) UUID ownerOrgId, String examCode, String title,
                     @Schema(implementation = Object.class) JsonNode blocks, List<String> questionTypeCodes, UUID createdBy, Instant createdAt, Instant updatedAt) {
        static LessonDto of(StrategyLessonVersionView v) {
            return new LessonDto(v.versionId(), v.lessonId(), v.versionNo(), v.status(), v.ownerOrgId().orElse(null), v.examCode(), v.title(), v.blocks(),
                    v.questionTypeCodes(), v.createdBy(), v.createdAt(), v.updatedAt());
        }
    }

    @Schema(name = "StrategyLessonSummary")
    record SummaryDto(UUID lessonId, UUID versionId, int versionNo, ContentStatus status, String examCode, String title, UUID createdBy, Instant updatedAt) {}

    @Schema(name = "StrategyLessonPage")
    record PageDto(List<SummaryDto> items, long total, int offset, int limit) {}

    @Schema(name = "StrategyLessonSubmitted")
    record SubmittedDto(UUID reviewId) {}

    private final StrategyService service;

    StrategyController(StrategyService service) {
        this.service = service;
    }

    @PostMapping
    @Operation(summary = "Create a strategy lesson (version 1, a draft)",
            description = "422 with errors[] (JSON paths such as blocks[2].url) when a block does not match its type, a video address is not YouTube/Vimeo, a file or embedded question is not usable, or no question type is given.")
    ResponseEntity<LessonDto> create(@RequestBody LessonRequest body) {
        return ResponseEntity.status(HttpStatus.CREATED).cacheControl(CacheControl.noStore()).body(LessonDto.of(service.create(toBody(body))));
    }

    @PutMapping("/{id}")
    @Operation(summary = "Edit a lesson: the open draft in place (200), or a new draft version when the latest is PUBLISHED (201)")
    ResponseEntity<LessonDto> update(@PathVariable UUID id, @RequestBody LessonRequest body) {
        StrategyService.Saved saved = service.update(id, toBody(body));
        return ResponseEntity.status(saved.newVersion() ? HttpStatus.CREATED : HttpStatus.OK).cacheControl(CacheControl.noStore()).body(LessonDto.of(saved.view()));
    }

    @GetMapping
    @Operation(summary = "List lessons (the latest version of each) the caller may read, newest change first")
    ResponseEntity<PageDto> list(@RequestParam(required = false) String exam, @RequestParam(required = false) ContentStatus status,
                                 @RequestParam(required = false) Integer page) {
        StrategyService.Page p = service.list(exam, status, page);
        List<SummaryDto> items = p.items().stream().map(s -> new SummaryDto(s.lessonId(), s.versionId(), s.versionNo(), s.status(), s.examCode(), s.title(),
                s.createdBy(), s.updatedAt())).toList();
        return ResponseEntity.ok().cacheControl(CacheControl.noStore()).body(new PageDto(items, p.total(), p.offset(), p.limit()));
    }

    @GetMapping("/{id}")
    @Operation(summary = "One lesson: its latest version, or the numbered one")
    ResponseEntity<LessonDto> get(@PathVariable UUID id, @RequestParam(required = false) Integer version) {
        return ResponseEntity.ok().cacheControl(CacheControl.noStore()).body(LessonDto.of(service.get(id, version)));
    }

    @GetMapping("/{id}/versions")
    @Operation(summary = "Every version of a lesson, oldest first")
    ResponseEntity<List<LessonDto>> versions(@PathVariable UUID id) {
        return ResponseEntity.ok().cacheControl(CacheControl.noStore()).body(service.versions(id).stream().map(LessonDto::of).toList());
    }

    @PostMapping("/{id}/submit")
    @Operation(summary = "Submit the open draft for review (refused at approval while an embedded question is not published)")
    ResponseEntity<SubmittedDto> submit(@PathVariable UUID id) {
        return ResponseEntity.status(HttpStatus.ACCEPTED).cacheControl(CacheControl.noStore()).body(new SubmittedDto(service.submit(id)));
    }

    private static StrategyService.Body toBody(LessonRequest r) {
        return new StrategyService.Body(r.examCode(), r.title(), r.blocks(), r.questionTypeCodes());
    }
}
