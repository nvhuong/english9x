package com.english9x.analytics.progress;

import com.english9x.analytics.progress.ProgressDtos.Progress;
import io.swagger.v3.oas.annotations.Operation;
import java.util.UUID;
import org.springframework.http.CacheControl;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RestController;

/** F24: the learner's progress grouped by the plan (or the seat) that grants each course; the same figures for an organisation's staff. */
@RestController
class ProgressController {

    private final ProgressService service;

    ProgressController(ProgressService service) {
        this.service = service;
    }

    @GetMapping("/api/me/progress")
    @Operation(summary = "My progress by plan",
            description = "One card per plan (ORDER) or seat (SEAT): activation, expiry, days left, courses used / allowed; per course the % done, chapters and lessons done, chapter tests, "
                    + "exams sat, latest / best score (PRELIMINARY or OFFICIAL), assignments not handed in, the lesson to resume; plans that ended are in `expired` (read only). "
                    + "Also the predicted-score series, study minutes per week, the streak.")
    ResponseEntity<Progress> mine() {
        return ResponseEntity.ok().cacheControl(CacheControl.noStore()).body(service.mine());
    }

    @GetMapping("/api/orgs/{id}/learners/{userId}/progress")
    @Operation(summary = "A learner's progress, for the ORG_ADMIN or a TEACHER of the organisation",
            description = "Only the seats the organisation issued and the work done in it. 403 outside an organisation context or without the role; 404 for another organisation, or for a person who is not a member.")
    ResponseEntity<Progress> ofLearner(@PathVariable UUID id, @PathVariable UUID userId) {
        return ResponseEntity.ok().cacheControl(CacheControl.noStore()).body(service.ofLearner(id, userId));
    }
}
