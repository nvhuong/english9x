package com.english9x.exam.internal;

import com.english9x.domain.exam.ResumeOutcome;
import com.english9x.exam.internal.ExamDtos.AttemptView;
import com.english9x.exam.internal.ExamDtos.AudioLink;
import com.english9x.exam.internal.ExamDtos.Result;
import io.swagger.v3.oas.annotations.Operation;
import java.util.UUID;
import org.springframework.http.CacheControl;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestPart;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;

/** The exam room's API: a learner's own attempts, nobody else's (another person's attempt is a 404). Nothing here ever carries the solution side. */
@RestController
@RequestMapping("/api/exam/attempts")
class ExamController {

    private final ExamService service;

    ExamController(ExamService service) {
        this.service = service;
    }

    private static <T> ResponseEntity<T> noStore(HttpStatus status, T body) {
        return ResponseEntity.status(status).cacheControl(CacheControl.noStore()).body(body);
    }

    @PostMapping
    @Operation(summary = "Start an attempt on a test version",
            description = "201 with the attempt (section list and the content of the first section). 409 with the attempt that is already IN_PROGRESS for this test. 403 without an entitlement.")
    ResponseEntity<AttemptView> start(@RequestBody ExamDtos.StartRequest body) {
        ExamService.Started s = service.start(body == null ? null : body.testVersionId());
        return noStore(s.created() ? HttpStatus.CREATED : HttpStatus.CONFLICT, s.view());
    }

    @GetMapping("/{id}")
    @Operation(summary = "The attempt as it stands now (current section and what is saved in it)")
    ResponseEntity<AttemptView> get(@PathVariable UUID id) {
        return noStore(HttpStatus.OK, service.view(id, currentUser()));
    }

    @PostMapping("/{id}/resume")
    @Operation(summary = "Come back to an attempt (counts as a resume)",
            description = "200 with the attempt. 409 with the submitted attempt when the time ran out meanwhile or the limit of resumes was passed (then it is submitted as interrupted).")
    ResponseEntity<AttemptView> resume(@PathVariable UUID id) {
        ExamService.Resumed r = service.resume(id);
        return noStore(r.outcome() == ResumeOutcome.CONTINUED ? HttpStatus.OK : HttpStatus.CONFLICT, r.view());
    }

    @PutMapping("/{id}/responses/{questionId}")
    @Operation(summary = "Save the answer to a question",
            description = "204. 409 when the answer was written after its section ended, or synced later than e9x.exam.late-sync-grace after it ended. The newer answeredAt wins.")
    ResponseEntity<Void> save(@PathVariable UUID id, @PathVariable UUID questionId, @RequestBody ExamDtos.SaveRequest body) {
        service.saveResponse(id, questionId, body);
        return ResponseEntity.noContent().cacheControl(CacheControl.noStore()).build();
    }

    @PostMapping("/{id}/sections/{sectionId}/finish")
    @Operation(summary = "Finish the current section early (the last one submits the attempt)")
    ResponseEntity<AttemptView> finish(@PathVariable UUID id, @PathVariable String sectionId) {
        return noStore(HttpStatus.OK, service.finishSection(id, sectionId));
    }

    @PostMapping("/{id}/submit")
    @Operation(summary = "Submit the attempt", description = "Idempotent: a second call answers 200 with the same result.")
    ResponseEntity<Result> submit(@PathVariable UUID id) {
        return noStore(HttpStatus.OK, service.submit(id));
    }

    @GetMapping("/{id}/audio/{mediaId}")
    @Operation(summary = "A one-time link to a recording of the current section", description = "410 when it was already given out.")
    ResponseEntity<AudioLink> audio(@PathVariable UUID id, @PathVariable UUID mediaId) {
        return noStore(HttpStatus.OK, service.audio(id, mediaId));
    }

    @PostMapping(path = "/{id}/recordings/{questionId}", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    @Operation(summary = "Upload the recording of a Speaking question (WebM/Opus, part \"file\")",
            description = "Only while the Speaking part runs, plus e9x.exam.recording-upload-tolerance. A new upload replaces the earlier one.")
    ResponseEntity<Void> recording(@PathVariable UUID id, @PathVariable UUID questionId, @RequestPart("file") MultipartFile file) {
        service.recording(id, questionId, file);
        return ResponseEntity.noContent().cacheControl(CacheControl.noStore()).build();
    }

    private static UUID currentUser() {
        return com.english9x.org.api.CurrentContextHolder.current()
                .orElseThrow(() -> new org.springframework.security.access.AccessDeniedException("no user")).userId();
    }
}
