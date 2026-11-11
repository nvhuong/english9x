package com.english9x.classroom.assignment;

import com.english9x.classroom.assignment.AssignmentDtos.AssignmentView;
import com.english9x.classroom.assignment.AssignmentDtos.CreateAssignmentRequest;
import com.english9x.classroom.assignment.AssignmentDtos.MyAssignment;
import com.english9x.classroom.assignment.AssignmentDtos.Report;
import io.swagger.v3.oas.annotations.Operation;
import jakarta.validation.Valid;
import java.util.List;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

/** F46: give work with a due date, see who handed in; the learner's list. */
@RestController
class AssignmentController {

    private final AssignmentService assignments;

    AssignmentController(AssignmentService assignments) {
        this.assignments = assignments;
    }

    @PostMapping("/api/orgs/{id}/classes/{cid}/assignments")
    @Operation(summary = "Give the class a lesson / chapter test (lessonId) or an exam (testVersionId) due at dueAt; ORG_ADMIN or the class's teacher")
    @ResponseStatus(HttpStatus.CREATED)
    AssignmentView create(@PathVariable UUID id, @PathVariable UUID cid, @Valid @RequestBody CreateAssignmentRequest body) {
        return assignments.create(id, cid, body.lessonId(), body.testVersionId(), body.dueAt());
    }

    @GetMapping("/api/orgs/{id}/classes/{cid}/assignments")
    List<AssignmentView> ofClass(@PathVariable UUID id, @PathVariable UUID cid) {
        return assignments.ofClass(id, cid);
    }

    @GetMapping("/api/orgs/{id}/assignments/{aid}")
    @Operation(summary = "Who handed in (SUBMITTED / LATE), who did not (MISSING after the due date, PENDING before)")
    Report report(@PathVariable UUID id, @PathVariable UUID aid) {
        return assignments.report(id, aid);
    }

    @GetMapping("/api/me/assignments")
    @Operation(summary = "My assignments in the organisation of the current context, with their status")
    List<MyAssignment> mine() {
        return assignments.mine();
    }
}
