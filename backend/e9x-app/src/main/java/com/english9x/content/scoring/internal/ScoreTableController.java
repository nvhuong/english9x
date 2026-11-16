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

/**
 * The CMS score-table API (F15). The author is the signed-in user. The default of an exam (scope EXAM) is English9x's; the override of one test (scope TEST) is
 * attached by the administrator of the test's owner ({@code /overrides}) and its rows are written by an AUTHOR of the exam like any table, then reviewed.
 */
@RestController
@RequestMapping("/api/cms/score-tables")
class ScoreTableController {

    @Schema(name = "ScoreTableRequest")
    record TableRequest(
            @Schema(description = "the exam (needed to make a table; fixed afterwards)", example = "IELTS", nullable = true) String examCode,
            @Schema(description = "one of the exam's score skills: IELTS LISTENING, READING_AC, READING_GT, WRITING, SPEAKING, OVERALL; TOEIC LISTENING, READING, TOTAL",
                    example = "LISTENING", nullable = true) String skill,
            @Schema(description = "EXAM, the default of the exam, or TEST, the override of one test (attached first with POST /overrides; it is then edited here). Never changes", example = "EXAM") String scope,
            @Schema(description = "the TEST (not a test version) of an override; only with scope TEST, and only its own", nullable = true) UUID testId,
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

    @Schema(name = "ScoreTableOverrideRequest")
    record OverrideRequest(@Schema(description = "the TEST (not a test version)") UUID testId,
                           @Schema(description = "a score of the test's exam that a table converts (not one a function computes)", example = "LISTENING") String skill) {}

    @Schema(name = "ScoreTableOverride")
    record OverrideDto(UUID tableId, UUID testId, @Schema(nullable = true) UUID ownerOrgId, String examCode, String skill,
                       @Schema(description = "the latest version; null until an author wrote the first one", nullable = true) UUID versionId,
                       @Schema(nullable = true) Integer versionNo, @Schema(nullable = true) ContentStatus status) {
        static OverrideDto of(ScoreTableRepository.TestOverride o) {
            return new OverrideDto(o.tableId(), o.testId(), o.ownerOrgId(), o.examCode(), o.skill(), o.versionId(), o.versionNo(), o.status());
        }
    }

    @Schema(name = "ScoreTableSubmitted")
    record SubmittedDto(UUID reviewId) {}

    private final ScoreTableService service;

    ScoreTableController(ScoreTableService service) {
        this.service = service;
    }

    @PostMapping
    @Operation(summary = "Create the conversion table of an exam skill (version 1, a draft)",
            description = "422 with errors[] (JSON paths such as rows[3].raw) when the skill is not one of the exam's or the rows are not a valid table (raw contiguous from 0). "
                    + "Scope TEST is 422 (field scope): an override is attached with POST /overrides. 409 score-table-exists (with tableId) when the exam skill already has a table.")
    ResponseEntity<TableDto> create(@RequestBody TableRequest body) {
        return ResponseEntity.status(HttpStatus.CREATED).cacheControl(CacheControl.noStore()).body(TableDto.of(service.create(toBody(body))));
    }

    @PutMapping("/{id}")
    @Operation(summary = "Edit a table: the open draft in place (200), or a new draft version when the latest is PUBLISHED (201)",
            description = "The scope is named and never changes. An override the administrator attached and nobody wrote yet gets its version 1 here (201).")
    ResponseEntity<TableDto> update(@PathVariable UUID id, @RequestBody TableRequest body) {
        ScoreTableService.Saved saved = service.update(id, toBody(body));
        return ResponseEntity.status(saved.newVersion() ? HttpStatus.CREATED : HttpStatus.OK).cacheControl(CacheControl.noStore()).body(TableDto.of(saved.view()));
    }

    @PostMapping("/overrides")
    @Operation(summary = "Attach the override of one skill to a test (the header only; an author then writes version 1 with PUT /{tableId})",
            description = "For the administrator of the test's owner (SYS_ADMIN for an English9x test, ORG_ADMIN of the organisation for its own), who can open the test. "
                    + "404 when the test is not one the caller can open; 422 when the skill is not a table score of the exam (or the Reading of the other variant); "
                    + "409 score-table-exists (with tableId) when the test has an override for the skill.")
    ResponseEntity<OverrideDto> attach(@RequestBody OverrideRequest body) {
        return ResponseEntity.status(HttpStatus.CREATED).cacheControl(CacheControl.noStore()).body(OverrideDto.of(service.attach(body.testId(), body.skill())));
    }

    @GetMapping("/overrides")
    @Operation(summary = "The overrides of a test, by skill, each with its latest version (none until an author wrote one)")
    ResponseEntity<List<OverrideDto>> overrides(@RequestParam(required = false) UUID testId) {
        return ResponseEntity.ok().cacheControl(CacheControl.noStore()).body(service.overrides(testId).stream().map(OverrideDto::of).toList());
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
