package com.english9x.content.question.internal;

import com.english9x.content.question.api.QuestionGroupVersionView;
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

/** The CMS API of question groups: the shared stimulus of several questions and the ordered question versions asked about it. Staff only; see {@link QuestionController}. */
@RestController
@RequestMapping("/api/cms/question-groups")
class GroupController {

    @Schema(name = "QuestionGroupRequest")
    record GroupRequest(
            @Schema(description = "the exam; fixed for the life of the group") String examCode,
            @Schema(implementation = Object.class, description = "title (required), instructions, passage, audioMediaId, imageMediaId: what the learner sees") JsonNode content,
            @Schema(implementation = Object.class, description = "transcript, notes: the solution side, never shown before submitting", nullable = true) JsonNode solution,
            @Schema(description = "the member question versions, in order; each one a question of this exam that the author can read") List<UUID> questionVersionIds) {}

    @Schema(name = "QuestionGroupVersion")
    record GroupVersionDto(UUID versionId, UUID groupId, int versionNo, ContentStatus status, @Schema(nullable = true) UUID ownerOrgId, String examCode,
                           @Schema(implementation = Object.class) JsonNode content, @Schema(implementation = Object.class) JsonNode solution, List<UUID> questionVersionIds, UUID createdBy,
                           Instant createdAt, Instant updatedAt) {
        static GroupVersionDto of(QuestionGroupVersionView v) {
            return new GroupVersionDto(v.versionId(), v.groupId(), v.versionNo(), v.status(), v.ownerOrgId().orElse(null), v.examCode(), v.content(),
                    v.solution(), v.questionVersionIds(), v.createdBy(), v.createdAt(), v.updatedAt());
        }
    }

    @Schema(name = "QuestionGroupSummary")
    record GroupSummaryDto(UUID groupId, UUID versionId, int versionNo, ContentStatus status, String examCode, @Schema(nullable = true) String title,
                           int members, UUID createdBy, Instant updatedAt) {}

    @Schema(name = "QuestionGroupPage")
    record GroupPageDto(List<GroupSummaryDto> items, int offset, int limit) {}

    private final GroupService service;

    GroupController(GroupService service) {
        this.service = service;
    }

    @PostMapping
    @Operation(summary = "Create a question group (version 1, a draft) with its members", description = "422 with errors[] by JSON path, as for a question.")
    ResponseEntity<GroupVersionDto> create(@RequestBody GroupRequest body) {
        return ResponseEntity.status(HttpStatus.CREATED).cacheControl(CacheControl.noStore())
                .body(GroupVersionDto.of(service.create(new GroupService.Body(body.examCode(), body.content(), body.solution(), body.questionVersionIds()))));
    }

    @PutMapping("/{id}")
    @Operation(summary = "Edit a group: the open draft in place (200), or a new draft version when the latest is PUBLISHED (201)")
    ResponseEntity<GroupVersionDto> update(@PathVariable UUID id, @RequestBody GroupRequest body) {
        GroupService.Saved saved = service.update(id, new GroupService.Body(body.examCode(), body.content(), body.solution(), body.questionVersionIds()));
        return ResponseEntity.status(saved.newVersion() ? HttpStatus.CREATED : HttpStatus.OK).cacheControl(CacheControl.noStore()).body(GroupVersionDto.of(saved.view()));
    }

    @GetMapping
    @Operation(summary = "List groups (the latest version of each) the caller may read")
    ResponseEntity<GroupPageDto> list(@RequestParam(required = false) String exam, @RequestParam(required = false) ContentStatus status,
                                      @RequestParam(required = false) Integer page, @RequestParam(required = false) Integer size) {
        GroupService.Page p = service.list(exam, status, page, size);
        List<GroupSummaryDto> items = p.items().stream().map(s -> new GroupSummaryDto(s.groupId(), s.versionId(), s.versionNo(), s.status(), s.examCode(),
                s.title(), s.members(), s.createdBy(), s.updatedAt())).toList();
        return ResponseEntity.ok().cacheControl(CacheControl.noStore()).body(new GroupPageDto(items, p.offset(), p.limit()));
    }

    @GetMapping("/{id}")
    @Operation(summary = "One group: its latest version, or the numbered one, with the solution side")
    ResponseEntity<GroupVersionDto> get(@PathVariable UUID id, @RequestParam(required = false) Integer version) {
        return ResponseEntity.ok().cacheControl(CacheControl.noStore()).body(GroupVersionDto.of(service.get(id, version)));
    }

    @GetMapping("/{id}/versions")
    @Operation(summary = "Every version of a group, oldest first")
    ResponseEntity<List<GroupVersionDto>> versions(@PathVariable UUID id) {
        return ResponseEntity.ok().cacheControl(CacheControl.noStore()).body(service.versions(id).stream().map(GroupVersionDto::of).toList());
    }

    @PostMapping("/{id}/submit")
    @Operation(summary = "Submit the open draft for review; it is approved only when every member question is PUBLISHED")
    ResponseEntity<QuestionController.SubmittedDto> submit(@PathVariable UUID id) {
        return ResponseEntity.status(HttpStatus.ACCEPTED).cacheControl(CacheControl.noStore()).body(new QuestionController.SubmittedDto(service.submit(id), null));
    }
}
