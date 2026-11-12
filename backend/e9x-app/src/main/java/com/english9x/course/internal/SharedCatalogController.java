package com.english9x.course.internal;

import com.english9x.course.internal.CourseRepository.QueueRow;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.media.Schema;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.springframework.http.CacheControl;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/** The English9x shared-catalogue queue (F21): the pending centre courses and the reviewer's decision. English9x REVIEWER of the exam only. */
@RestController
@RequestMapping("/api/admin/shared-catalog")
class SharedCatalogController {

    @Schema(name = "SharedCatalogQueueItem")
    record QueueItemDto(UUID courseId, UUID versionId, String code, String examCode, int versionNo, String title, String authorDisplay, UUID ownerOrgId,
                        String ownerName, String sharedStatus, Instant updatedAt) {
        static QueueItemDto of(QueueRow r) {
            return new QueueItemDto(r.courseId(), r.versionId(), r.code(), r.examCode(), r.versionNo(), r.title(), r.authorDisplay(), r.ownerOrgId(),
                    r.ownerName(), r.sharedStatus(), r.updatedAt());
        }
    }

    @Schema(name = "SharedCatalogDecision")
    record DecisionDto(UUID courseId, String sharedStatus) {}

    private final SharedCatalogService service;

    SharedCatalogController(SharedCatalogService service) {
        this.service = service;
    }

    @GetMapping
    @Operation(summary = "The shared-catalogue queue: PENDING centre courses with the version in force and the owning centre")
    ResponseEntity<List<QueueItemDto>> queue(@RequestParam(required = false) String exam) {
        return ResponseEntity.ok().cacheControl(CacheControl.noStore()).body(service.queue(exam).stream().map(QueueItemDto::of).toList());
    }

    @PostMapping("/{id}/approve")
    @Operation(summary = "Approve a pending course into the shared catalogue (PENDING -> APPROVED); it becomes visible to B2C and every centre")
    ResponseEntity<DecisionDto> approve(@PathVariable UUID id) {
        return ResponseEntity.ok().cacheControl(CacheControl.noStore()).body(new DecisionDto(id, service.approve(id)));
    }

    @PostMapping("/{id}/reject")
    @Operation(summary = "Decline a course (PENDING or APPROVED -> REJECTED); the owner may revise and offer it again")
    ResponseEntity<DecisionDto> reject(@PathVariable UUID id) {
        return ResponseEntity.ok().cacheControl(CacheControl.noStore()).body(new DecisionDto(id, service.reject(id)));
    }
}
