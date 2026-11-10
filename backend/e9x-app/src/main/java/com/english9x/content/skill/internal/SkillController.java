package com.english9x.content.skill.internal;

import com.english9x.content.skill.api.GradingSkillVersionView;
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

/** The CMS grading-skill API (F16). The author is the signed-in user; the owner is the bound context. For the AUTHOR, REVIEWER (and, to read PUBLISHED ones, TEACHER) of the exam. */
@RestController
@RequestMapping("/api/cms/grading-skills")
class SkillController {

    @Schema(name = "GradingSkillRequest")
    record SkillRequest(
            @Schema(description = "the Writing or Speaking question type the skill grades (needed to make a skill; fixed afterwards)", example = "IELTS_W_TASK2", nullable = true) String questionTypeCode,
            @Schema(description = "markdown") String rubric,
            @Schema(implementation = Criterion[].class, description = "the scale of each criterion: code, name, min, max, step (step divides min..max)") JsonNode criteria,
            @Schema(description = "Mustache variables {{rubric}} {{samples}} {{task}} {{response}}, all four used; nothing else") String promptTemplate,
            @Schema(implementation = Sample[].class, description = "scored examples: band, exactly one of text and mediaId, commentary") JsonNode samples,
            @Schema(implementation = Object.class, description = "JSON Schema (2020-12) of the AI's answer; declares a property for every criterion code; refers only to itself") JsonNode resultSchema) {}

    @Schema(name = "GradingCriterion")
    record Criterion(String code, String name, double min, double max, double step) {}

    @Schema(name = "GradingSample")
    record Sample(double band, @Schema(nullable = true) String text, @Schema(nullable = true) UUID mediaId, @Schema(nullable = true) String commentary) {}

    @Schema(name = "GradingSkillVersion")
    record SkillDto(UUID versionId, UUID skillId, int versionNo, ContentStatus status, @Schema(nullable = true) UUID ownerOrgId, String examCode, String questionTypeCode,
                    String rubric, @Schema(implementation = Criterion[].class) JsonNode criteria, String promptTemplate, @Schema(implementation = Sample[].class) JsonNode samples,
                    @Schema(implementation = Object.class) JsonNode resultSchema, UUID createdBy, Instant createdAt, Instant updatedAt) {
        static SkillDto of(GradingSkillVersionView v) {
            return new SkillDto(v.versionId(), v.skillId(), v.versionNo(), v.status(), v.ownerOrgId().orElse(null), v.examCode(), v.questionTypeCode(), v.rubric(),
                    v.criteria(), v.promptTemplate(), v.samples(), v.resultSchema(), v.createdBy(), v.createdAt(), v.updatedAt());
        }
    }

    @Schema(name = "GradingSkillSummary")
    record SummaryDto(UUID skillId, UUID versionId, int versionNo, ContentStatus status, String examCode, String questionTypeCode, UUID createdBy, Instant updatedAt) {}

    @Schema(name = "GradingSkillPage")
    record PageDto(List<SummaryDto> items, long total, int offset, int limit) {}

    @Schema(name = "GradingSkillSubmitted")
    record SubmittedDto(UUID reviewId) {}

    @Schema(name = "GradingSkillDryRunRequest")
    record DryRunRequest(@Schema(description = "the answer as text (Writing)", nullable = true) String response,
                         @Schema(description = "or as a recording (Speaking): a file of the exam", nullable = true) UUID mediaId) {}

    private final SkillService service;

    SkillController(SkillService service) {
        this.service = service;
    }

    @PostMapping
    @Operation(summary = "Create the grading skill of a question type (version 1, a draft)",
            description = "422 with errors[] (JSON paths such as resultSchema.properties.task_response, criteria[1].step) when the content does not match its form; 409 grading-skill-exists (with skillId) when the owner has a skill for the type.")
    ResponseEntity<SkillDto> create(@RequestBody SkillRequest body) {
        return ResponseEntity.status(HttpStatus.CREATED).cacheControl(CacheControl.noStore()).body(SkillDto.of(service.create(toBody(body))));
    }

    @PutMapping("/{id}")
    @Operation(summary = "Edit a skill: the open draft in place (200), or a new draft version when the latest is PUBLISHED (201)")
    ResponseEntity<SkillDto> update(@PathVariable UUID id, @RequestBody SkillRequest body) {
        SkillService.Saved saved = service.update(id, toBody(body));
        return ResponseEntity.status(saved.newVersion() ? HttpStatus.CREATED : HttpStatus.OK).cacheControl(CacheControl.noStore()).body(SkillDto.of(saved.view()));
    }

    @GetMapping
    @Operation(summary = "List grading skills (the latest version of each) the caller may read, newest change first")
    ResponseEntity<PageDto> list(@RequestParam(required = false) String exam, @RequestParam(required = false) ContentStatus status,
                                 @RequestParam(required = false) Integer page) {
        SkillService.Page p = service.list(exam, status, page);
        List<SummaryDto> items = p.items().stream().map(s -> new SummaryDto(s.skillId(), s.versionId(), s.versionNo(), s.status(), s.examCode(), s.questionTypeCode(),
                s.createdBy(), s.updatedAt())).toList();
        return ResponseEntity.ok().cacheControl(CacheControl.noStore()).body(new PageDto(items, p.total(), p.offset(), p.limit()));
    }

    @GetMapping("/{id}")
    @Operation(summary = "One skill: its latest version, or the numbered one")
    ResponseEntity<SkillDto> get(@PathVariable UUID id, @RequestParam(required = false) Integer version) {
        return ResponseEntity.ok().cacheControl(CacheControl.noStore()).body(SkillDto.of(service.get(id, version)));
    }

    @GetMapping("/{id}/versions")
    @Operation(summary = "Every version of a skill, oldest first")
    ResponseEntity<List<SkillDto>> versions(@PathVariable UUID id) {
        return ResponseEntity.ok().cacheControl(CacheControl.noStore()).body(service.versions(id).stream().map(SkillDto::of).toList());
    }

    @PostMapping("/{id}/submit")
    @Operation(summary = "Submit the open draft for review")
    ResponseEntity<SubmittedDto> submit(@PathVariable UUID id) {
        return ResponseEntity.status(HttpStatus.ACCEPTED).cacheControl(CacheControl.noStore()).body(new SubmittedDto(service.submit(id)));
    }

    @PostMapping("/{id}/dry-run")
    @Operation(summary = "Run the skill on a sample answer, saving no score (needs the AI grader: 501 until it exists)",
            description = "501 dry-run-unavailable until the AI grader (P4.2) is built; nothing is sent to any service meanwhile.")
    ResponseEntity<Void> dryRun(@PathVariable UUID id, @RequestBody DryRunRequest body) {
        service.dryRun(id, new SkillService.DryRun(body.response(), body.mediaId()));
        return ResponseEntity.status(HttpStatus.NOT_IMPLEMENTED).build();
    }

    private static SkillService.Body toBody(SkillRequest r) {
        return new SkillService.Body(r.questionTypeCode(), r.rubric(), r.criteria(), r.promptTemplate(), r.samples(), r.resultSchema());
    }
}
