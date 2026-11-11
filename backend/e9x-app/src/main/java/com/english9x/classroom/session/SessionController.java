package com.english9x.classroom.session;

import com.english9x.classroom.session.SessionDtos.CreateSessionRequest;
import com.english9x.classroom.session.SessionDtos.ScheduleItem;
import com.english9x.classroom.session.SessionDtos.SessionView;
import io.swagger.v3.oas.annotations.Operation;
import jakarta.validation.Valid;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

/** F47: the class schedule (online with a link, offline in a room) and each person's own schedule. */
@RestController
class SessionController {

    private final SessionService sessions;

    SessionController(SessionService sessions) {
        this.sessions = sessions;
    }

    @PostMapping("/api/orgs/{id}/classes/{cid}/sessions")
    @Operation(summary = "Schedule a session of the class: OFFLINE (room) or ONLINE (an https meetingUrl is required); ORG_ADMIN or the class's teacher")
    @ResponseStatus(HttpStatus.CREATED)
    SessionView create(@PathVariable UUID id, @PathVariable UUID cid, @Valid @RequestBody CreateSessionRequest body) {
        return sessions.create(id, cid, body);
    }

    @GetMapping("/api/orgs/{id}/classes/{cid}/sessions")
    @Operation(summary = "The sessions of the class, earliest first; the managers and the members of the class see them")
    List<SessionView> ofClass(@PathVariable UUID id, @PathVariable UUID cid) {
        return sessions.ofClass(id, cid);
    }

    @GetMapping("/api/me/schedule")
    @Operation(summary = "My sessions (as a learner or as a teacher) starting in [from, to) in the organisation of the current context; defaults: from now, one month")
    List<ScheduleItem> mine(@RequestParam(required = false) Instant from, @RequestParam(required = false) Instant to) {
        return sessions.mine(from, to);
    }
}
