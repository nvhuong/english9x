package com.english9x.content.scoring.internal;

import com.english9x.content.scoring.api.ScoreRow;
import com.english9x.content.scoring.api.ScoreScope;
import com.english9x.content.scoring.api.ScoreTableVersionView;
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

/** The CMS score-table API (F15). The author is the signed-in user; the owner is English9x (the exam default). For the AUTHOR and REVIEWER of the exam only. */
@RestController
@RequestMapping("/api/cms/score-tables")
class ScoreTableController {

    @Schema(name = "ScoreTableRequest")
    record TableRequest(
            @Schema(description = "the exam (needed to make a table; fixed afterwards)", example = "IELTS", nullable = true) String examCode,
            @Schema(description = "one of the exam's score skills: IELTS LISTENING, READING_AC, READING_GT, WRITING, SPEAKING, OVERALL; TOEIC LISTENING, READING, TOTAL",
                    example = "LISTENING", nullable = true) String skill,
            @Schema(description = "EXAM, the default of the exam. TEST (the override of one test) answers 409 until tests can be built (P2.6)", example = "EXAM") String scope,
            @Schema(description = "the TEST (not a test version) of an override; only with scope TEST", nullable = true) UUID testId,
            @Schema(implementation = ScoreRow[].class, description = "raw correct answers to scaled score; raw contiguous from 0, scaled never decreasing") JsonNode rows) {}

    @Schema(name = "ScoreTableVersion")
    record TableDto(UUID versionId, UUID tableId, int versionNo, ContentStatus status, @Schema(nullable = true) UUID ownerOrgId, String examCode, String skill,
                    ScoreScope scope, @Schema(nullable = true) UUID testId, String function, List<ScoreRow> rows, UUID createdBy, Instant createdAt, Instant updatedAt) {
        static TableDto of(ScoreTableVersionView v) {
            return new TableDto(v.versionId(), v.tableId(), v.versionNo(), v.status(), v.ownerOrgId().orElse(null), v.examCode(), v.skill(), v.scope(),
                    v.testId().orElse(null), v.function(), v.rows(), v.createdBy(), v.createdAt(), v.updatedAt());
        }
    }

    @Schema(name = "ScoreTableSummary")
    record SummaryDto(UUID tableId, UUID versionId, int versionNo, ContentStatus status, String examCode, String skill, ScoreScope scope, String function,
                      int rowCount, UUID createdBy, Instant updatedAt) {}

    @Schema(name = "ScoreTablePage")
    record PageDto(List<SummaryDto> items, long total, int offset, int limit) {}

    @Schema(name = "ScoreTableSubmitted")
    record SubmittedDto(UUID reviewId) {}

    private final ScoreTableService service;

    ScoreTableController(ScoreTableService service) {
        this.service = service;
    }

    @PostMapping
    @Operation(summary = "Create the conversion table of an exam skill (version 1, a draft)",
            description = "422 with errors[] (JSON paths such as rows[3].raw) when the skill is not one of the exam's or the rows are not a valid table (raw contiguous from 0). "
                    + "409 test-scope-unavailable for scope TEST; 409 score-table-exists (with tableId) when the exam skill already has a table.")
    ResponseEntity<TableDto> create(@RequestBody TableRequest body) {
        return ResponseEntity.status(HttpStatus.CREATED).cacheControl(CacheControl.noStore()).body(TableDto.of(service.create(toBody(body))));
    }

    @PutMapping("/{id}")
    @Operation(summary = "Edit a table: the open draft in place (200), or a new draft version when the latest is PUBLISHED (201)")
    ResponseEntity<TableDto> update(@PathVariable UUID id, @RequestBody TableRequest body) {
        ScoreTableService.Saved saved = service.update(id, toBody(body));
        return ResponseEntity.status(saved.newVersion() ? HttpStatus.CREATED : HttpStatus.OK).cacheControl(CacheControl.noStore()).body(TableDto.of(saved.view()));
    }

    @GetMapping
    @Operation(summary = "List score tables (the latest version of each) the caller may read, newest change first")
    ResponseEntity<PageDto> list(@RequestParam(required = false) String exam, @RequestParam(required = false) ContentStatus status,
                                 @RequestParam(required = false) Integer page) {
        ScoreTableService.Page p = service.list(exam, status, page);
        List<SummaryDto> items = p.items().stream().map(s -> new SummaryDto(s.tableId(), s.versionId(), s.versionNo(), s.status(), s.examCode(), s.skill(), s.scope(),
                s.function(), s.rowCount(), s.createdBy(), s.updatedAt())).toList();
        return ResponseEntity.ok().cacheControl(CacheControl.noStore()).body(new PageDto(items, p.total(), p.offset(), p.limit()));
    }

    @GetMapping("/{id}")
    @Operation(summary = "One table: its latest version, or the numbered one")
    ResponseEntity<TableDto> get(@PathVariable UUID id, @RequestParam(required = false) Integer version) {
        return ResponseEntity.ok().cacheControl(CacheControl.noStore()).body(TableDto.of(service.get(id, version)));
    }

    @GetMapping("/{id}/versions")
    @Operation(summary = "Every version of a table, oldest first")
    ResponseEntity<List<TableDto>> versions(@PathVariable UUID id) {
        return ResponseEntity.ok().cacheControl(CacheControl.noStore()).body(service.versions(id).stream().map(TableDto::of).toList());
    }

    @PostMapping("/{id}/submit")
    @Operation(summary = "Submit the open draft for review")
    ResponseEntity<SubmittedDto> submit(@PathVariable UUID id) {
        return ResponseEntity.status(HttpStatus.ACCEPTED).cacheControl(CacheControl.noStore()).body(new SubmittedDto(service.submit(id)));
    }

    private static ScoreTableService.Body toBody(TableRequest r) {
        return new ScoreTableService.Body(r.examCode(), r.skill(), r.scope(), r.testId(), r.rows());
    }
}
