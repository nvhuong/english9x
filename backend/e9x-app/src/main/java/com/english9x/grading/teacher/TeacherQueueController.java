package com.english9x.grading.teacher;

import com.english9x.grading.teacher.TeacherDtos.AdminQueueItemView;
import com.english9x.grading.teacher.TeacherDtos.Detail;
import com.english9x.grading.teacher.TeacherDtos.QueueItemView;
import com.english9x.grading.teacher.TeacherDtos.ReassignRequest;
import com.english9x.grading.teacher.TeacherDtos.ReviewRequest;
import com.english9x.grading.teacher.TeacherDtos.ReviewResult;
import java.util.List;
import java.util.UUID;
import org.springframework.http.CacheControl;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/** The teachers' grading queue (F35) and the administrators' hand-over. Who sees and may do what is decided by the database (Row-Level Security), not here. */
@RestController
class TeacherQueueController {

    private final TeacherQueueService queue;

    TeacherQueueController(TeacherQueueService queue) {
        this.queue = queue;
    }

    /** The submissions a teacher may take (their side's OPEN ones) and the ones they hold, oldest first; {@code exam} narrows it. */
    @GetMapping("/api/teacher/queue")
    List<QueueItemView> list(@RequestParam(required = false) String exam) {
        return queue.list(exam);
    }

    /** Takes a submission. 409 when somebody else took it first (it is theirs and hidden from everybody else). */
    @PostMapping("/api/teacher/queue/{id}/claim")
    QueueItemView claim(@PathVariable UUID id) {
        return queue.claim(id);
    }

    /** Puts a held submission back in the queue. */
    @PostMapping("/api/teacher/queue/{id}/release")
    QueueItemView release(@PathVariable UUID id) {
        return queue.release(id);
    }

    /** The work of a submission the caller holds: the answers, the AI's judgement, the criteria to score. */
    @GetMapping("/api/teacher/queue/{id}")
    Detail detail(@PathVariable UUID id) {
        return queue.detail(id);
    }

    @GetMapping("/api/teacher/queue/{id}/recordings/{questionId}")
    ResponseEntity<byte[]> recording(@PathVariable UUID id, @PathVariable UUID questionId) {
        TeacherQueueService.Recording r = queue.recording(id, questionId);
        return ResponseEntity.ok().cacheControl(CacheControl.noStore()).contentType(MediaType.parseMediaType(r.mime() == null ? "audio/webm" : r.mime())).body(r.bytes());
    }

    /** Grades one skill: {@code {skill, criteria: {code: score}, comment}}. The teacher's score is the official one. */
    @PostMapping("/api/teacher/queue/{id}/review")
    ReviewResult review(@PathVariable UUID id, @RequestBody ReviewRequest body) {
        return queue.review(id, body);
    }

    /** The administrator's view of the queue of their side (SYS_ADMIN for English9x's, ORG_ADMIN for the centre's). */
    @GetMapping("/api/admin/queue")
    List<AdminQueueItemView> adminList(@RequestParam(required = false) String exam, @RequestParam(required = false) String state) {
        return queue.adminList(exam, state);
    }

    /** Hands a submission to a teacher of the side; the deadline starts again. */
    @PostMapping("/api/admin/queue/{id}/reassign")
    AdminQueueItemView reassign(@PathVariable UUID id, @RequestBody ReassignRequest body) {
        return queue.reassign(id, body == null ? null : body.teacherId());
    }
}
