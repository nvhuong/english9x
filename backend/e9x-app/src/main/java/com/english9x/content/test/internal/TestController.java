package com.english9x.content.test.internal;

import com.english9x.workflow.api.ContentStatus;
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
 * The CMS API of tests: the builder. Staff only (an AUTHOR or REVIEWER of the exam, in the context of the owner). The learner never reaches a test through
 * here: the exam room reads {@code TestVersions#forExamRoom}.
 */
@RestController
@RequestMapping("/api/cms/tests")
class TestController {

    @Schema(name = "CmsTestCreateRequest")
    record CreateRequest(
            @Schema(description = "the exam; fixed for the life of the test", example = "IELTS") String examCode,
            @Schema(description = "IELTS: AC or GT; an exam with no variants: null", nullable = true, example = "AC") String variant,
            @Schema(description = "the name the learner sees") String title) {}

    @Schema(name = "CmsTestPartRequest")
    record PartRequest(
            @Schema(description = "a section of the exam: a TOEIC part (P1..P7) or an IELTS skill (LISTENING, READING, WRITING, SPEAKING)") String sectionCode,
            @Schema(description = "the time limit of the section, in seconds") Integer durationSec,
            @Schema(description = "the group versions asked in this part, in order; each one a question group of this exam that the author can read") List<UUID> groups) {}

    @Schema(name = "CmsTestScoreOverrideRequest")
    record OverrideRequest(String skill, UUID scoreTableId) {}

    @Schema(name = "CmsTestStructureRequest")
    record StructureRequest(
            @Schema(description = "null keeps the title", nullable = true) String title,
            List<PartRequest> parts,
            @Schema(description = "how many times an attempt may be reopened; null = the default of the exam", nullable = true) Integer maxResumes,
            @Schema(description = "conversion tables of TEST scope of this test that the structure declares; each must exist for this test and skill", nullable = true) List<OverrideRequest> scoreTableOverrides) {}

    @Schema(name = "CmsTestGroup")
    record GroupDto(UUID groupVersionId, @Schema(nullable = true) UUID groupId, @Schema(nullable = true) String title, int questions) {}

    @Schema(name = "CmsTestPart")
    record PartDto(int position, String sectionCode, @Schema(nullable = true) String skill, int durationSec, List<GroupDto> groups, int questions) {}

    @Schema(name = "CmsTestIssue")
    record IssueDto(@Schema(nullable = true) String sectionCode, @Schema(nullable = true) String skill, String code, String message) {}

    @Schema(name = "CmsTestSectionCheck")
    record SectionCheckDto(String sectionCode, String skill, int expected, int actual, @Schema(description = "OK, MISSING, SHORT or OVER") String state) {}

    @Schema(name = "CmsTestOverride")
    record OverrideDto(UUID scoreTableId, String skill) {}

    @Schema(name = "CmsTestVersion")
    record VersionDto(UUID versionId, UUID testId, int versionNo, ContentStatus status, @Schema(nullable = true) UUID ownerOrgId, String examCode,
                      @Schema(nullable = true) String variant, String title, @Schema(nullable = true) Integer maxResumes, List<PartDto> parts,
                      @Schema(description = "one line per section of the exam: what it needs and what the test has") List<SectionCheckDto> sections,
                      @Schema(description = "count mismatches: they warn, they do not stop a review") List<IssueDto> warnings,
                      @Schema(description = "a skill with no question: the test cannot be sent for review") List<IssueDto> blockers,
                      List<OverrideDto> scoreTableOverrides, UUID createdBy, Instant createdAt, Instant updatedAt) {
        static VersionDto of(TestService.Detail d) {
            var v = d.view();
            return new VersionDto(v.versionId(), v.testId(), v.versionNo(), v.status(), v.ownerOrgId().orElse(null), v.examCode(), v.variant(), v.title(),
                    v.maxResumes(),
                    d.parts().stream().map(p -> new PartDto(p.position(), p.sectionCode(), p.skill(), p.durationSec(),
                            p.groups().stream().map(g -> new GroupDto(g.groupVersionId(), g.groupId(), g.title(), g.questions())).toList(), p.questions())).toList(),
                    d.report().sections().stream().map(s -> new SectionCheckDto(s.sectionCode(), s.skill(), s.expected(), s.actual(), s.state())).toList(),
                    d.report().warnings().stream().map(i -> new IssueDto(i.sectionCode(), i.skill(), i.code(), i.message())).toList(),
                    d.report().blockers().stream().map(i -> new IssueDto(i.sectionCode(), i.skill(), i.code(), i.message())).toList(),
                    d.overrides().stream().map(o -> new OverrideDto(o.scoreTableId(), o.skill())).toList(),
                    v.createdBy(), v.createdAt(), v.updatedAt());
        }
    }

    @Schema(name = "CmsTestSummary")
    record SummaryDto(UUID testId, UUID versionId, int versionNo, ContentStatus status, String examCode, @Schema(nullable = true) String variant, String title,
                      int parts, UUID createdBy, Instant updatedAt) {}

    @Schema(name = "CmsTestPage")
    record PageDto(List<SummaryDto> items, int offset, int limit) {}

    @Schema(name = "CmsTestSubmitted")
    record SubmittedDto(UUID reviewId) {}

    private final TestService service;

    TestController(TestService service) {
        this.service = service;
    }

    @PostMapping
    @Operation(summary = "Create a test (version 1, an empty draft)", description = "422 with errors[] by JSON path (examCode, variant, title).")
    ResponseEntity<VersionDto> create(@RequestBody CreateRequest body) {
        return ResponseEntity.status(HttpStatus.CREATED).cacheControl(CacheControl.noStore())
                .body(VersionDto.of(service.create(new TestService.CreateBody(body.examCode(), body.variant(), body.title()))));
    }

    @PutMapping("/{id}/structure")
    @Operation(summary = "Set the structure: the open draft in place (200), or a new draft version when the latest is PUBLISHED (201)",
            description = "422 with errors[] by JSON path. A count that differs from the exam's sections is not an error: it comes back as warnings.")
    ResponseEntity<VersionDto> structure(@PathVariable UUID id, @RequestBody StructureRequest body) {
        TestService.Saved saved = service.update(id, new TestService.StructureBody(body.title(),
                body.parts() == null ? null : body.parts().stream().map(p -> p == null ? null : new TestService.PartBody(p.sectionCode(), p.durationSec(), p.groups())).toList(),
                body.maxResumes(),
                body.scoreTableOverrides() == null ? null : body.scoreTableOverrides().stream().map(o -> o == null ? null : new TestService.OverrideBody(o.skill(), o.scoreTableId())).toList()));
        return ResponseEntity.status(saved.newVersion() ? HttpStatus.CREATED : HttpStatus.OK).cacheControl(CacheControl.noStore()).body(VersionDto.of(saved.detail()));
    }

    @GetMapping
    @Operation(summary = "List tests (the latest version of each) the caller may read")
    ResponseEntity<PageDto> list(@RequestParam(required = false) String exam, @RequestParam(required = false) ContentStatus status,
                                 @RequestParam(required = false) Integer page, @RequestParam(required = false) Integer size) {
        TestService.Page p = service.list(exam, status, page, size);
        List<SummaryDto> items = p.items().stream().map(s -> new SummaryDto(s.testId(), s.versionId(), s.versionNo(), s.status(), s.examCode(), s.variant(),
                s.title(), s.parts(), s.createdBy(), s.updatedAt())).toList();
        return ResponseEntity.ok().cacheControl(CacheControl.noStore()).body(new PageDto(items, p.offset(), p.limit()));
    }

    @GetMapping("/{id}")
    @Operation(summary = "One test: its latest version, or the numbered one, with the check against the exam")
    ResponseEntity<VersionDto> get(@PathVariable UUID id, @RequestParam(required = false) Integer version) {
        return ResponseEntity.ok().cacheControl(CacheControl.noStore()).body(VersionDto.of(service.get(id, version)));
    }

    @GetMapping("/{id}/versions")
    @Operation(summary = "Every version of a test, oldest first")
    ResponseEntity<List<VersionDto>> versions(@PathVariable UUID id) {
        return ResponseEntity.ok().cacheControl(CacheControl.noStore()).body(service.versions(id).stream().map(VersionDto::of).toList());
    }

    @PostMapping("/{id}/submit")
    @Operation(summary = "Submit the open draft for review", description = "422 test-skill-missing while a skill of the exam has no question. The review approves it only when every group is PUBLISHED.")
    ResponseEntity<SubmittedDto> submit(@PathVariable UUID id) {
        return ResponseEntity.status(HttpStatus.ACCEPTED).cacheControl(CacheControl.noStore()).body(new SubmittedDto(service.submit(id)));
    }
}
