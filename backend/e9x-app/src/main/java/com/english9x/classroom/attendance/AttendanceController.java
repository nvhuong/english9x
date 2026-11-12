package com.english9x.classroom.attendance;

import com.english9x.classroom.attendance.AttendanceDtos.Mark;
import com.english9x.classroom.attendance.AttendanceDtos.Report;
import com.english9x.classroom.attendance.AttendanceDtos.Sheet;
import io.swagger.v3.oas.annotations.Operation;
import jakarta.validation.Valid;
import java.util.List;
import java.util.UUID;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;

/** F48: take attendance (four states) and read the attendance rate of each learner. */
@RestController
class AttendanceController {

    private final AttendanceService attendance;

    AttendanceController(AttendanceService attendance) {
        this.attendance = attendance;
    }

    @PutMapping("/api/orgs/{id}/classes/{cid}/sessions/{sid}/attendance")
    @Operation(summary = "Record (or correct) the attendance of members of the class at the session; ORG_ADMIN or the class's teacher. Answers with the whole sheet")
    Sheet mark(@PathVariable UUID id, @PathVariable UUID cid, @PathVariable UUID sid, @RequestBody List<@Valid Mark> body) {
        return attendance.mark(id, cid, sid, body);
    }

    @GetMapping("/api/orgs/{id}/classes/{cid}/sessions/{sid}/attendance")
    @Operation(summary = "The sheet of the session: every member with their mark (status null: not marked yet); ORG_ADMIN or the class's teacher")
    Sheet sheet(@PathVariable UUID id, @PathVariable UUID cid, @PathVariable UUID sid) {
        return attendance.sheet(id, cid, sid);
    }

    @GetMapping("/api/orgs/{id}/classes/{cid}/attendance")
    @Operation(summary = "The attendance rate of each learner of the class over its marked sessions: (present + late) / (present + late + absent), excused left out")
    Report report(@PathVariable UUID id, @PathVariable UUID cid) {
        return attendance.report(id, cid);
    }
}
