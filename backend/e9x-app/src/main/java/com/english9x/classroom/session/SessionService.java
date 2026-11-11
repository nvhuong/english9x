package com.english9x.classroom.session;

import com.english9x.audit.api.AuditLog;
import com.english9x.classroom.klass.ClassAccess;
import com.english9x.classroom.klass.ClassProblems;
import com.english9x.classroom.klass.ClassRepository;
import com.english9x.classroom.session.SessionDtos.CreateSessionRequest;
import com.english9x.classroom.session.SessionDtos.Mode;
import com.english9x.classroom.session.SessionDtos.ScheduleItem;
import com.english9x.classroom.session.SessionDtos.SessionView;
import java.net.URI;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * The schedule of a class: OFFLINE sessions (a room) and ONLINE ones (an https link, any domain). The ORG_ADMIN and the class's teacher write; the members
 * of the class read; everybody reads their own schedule (as a learner or as a teacher) in the organisation of the context.
 */
@Service
class SessionService {

    /** The widest range {@code /api/me/schedule} answers for. */
    static final Duration MAX_RANGE = Duration.ofDays(366);
    private static final Duration DEFAULT_RANGE = Duration.ofDays(31);

    private final SessionRepository repo;
    private final ClassAccess access;
    private final ClassRepository classes;
    private final AuditLog audit;
    private final Clock clock;

    SessionService(SessionRepository repo, ClassAccess access, ClassRepository classes, AuditLog audit, Clock clock) {
        this.repo = repo;
        this.access = access;
        this.classes = classes;
        this.audit = audit;
        this.clock = clock;
    }

    @Transactional
    SessionView create(UUID orgId, UUID classId, CreateSessionRequest req) {
        access.managed(orgId, classId);
        if (!req.endsAt().isAfter(req.startsAt())) {
            throw ClassProblems.unprocessable("invalid-session", "Invalid session", "endsAt", "BeforeStart", "the session must end after it starts");
        }
        String room = blankToNull(req.room());
        String link = blankToNull(req.meetingUrl());
        if (req.mode() == Mode.ONLINE) {
            if (link == null || !isHttps(link)) {
                throw ClassProblems.unprocessable("online-needs-https-link", "An online session needs an https link", "meetingUrl", "HttpsRequired",
                        "an online session needs a meeting link that starts with https://");
            }
        } else if (link != null) {
            throw ClassProblems.unprocessable("offline-has-no-link", "An offline session has no link", "meetingUrl", "NotForOffline", "an offline session takes a room, not a meeting link");
        }
        SessionView s = repo.insert(classId, orgId, req.mode(), req.startsAt(), req.endsAt(), room, link, access.actingUser());
        Map<String, Object> detail = new LinkedHashMap<>();
        detail.put("classId", classId.toString());
        detail.put("mode", req.mode().name());
        detail.put("startsAt", req.startsAt().toString());
        audit.record("session.create", "class-session", s.id(), detail);
        return s;
    }

    /** The sessions of a class for whoever may see the class: a manager sees all, a member of the class theirs, anybody else none. */
    @Transactional(readOnly = true)
    List<SessionView> ofClass(UUID orgId, UUID classId) {
        access.inContext(orgId);
        classes.find(orgId, classId).orElseThrow(ClassProblems::notFound);
        return repo.ofClass(classId);
    }

    /** My sessions starting in [from, to) in the organisation of the context: from defaults to now, to to a month later. */
    @Transactional(readOnly = true)
    List<ScheduleItem> mine(Instant from, Instant to) {
        Instant f = from != null ? from : clock.instant();
        Instant t = to != null ? to : f.plus(DEFAULT_RANGE);
        if (!t.isAfter(f)) {
            throw ClassProblems.unprocessable("invalid-range", "Invalid range", "to", "BeforeFrom", "to must be after from");
        }
        if (Duration.between(f, t).compareTo(MAX_RANGE) > 0) {
            throw ClassProblems.unprocessable("invalid-range", "Range too wide", "to", "TooWide", "the range is at most 366 days");
        }
        return repo.mine(f, t);
    }

    private static String blankToNull(String s) {
        return s == null || s.isBlank() ? null : s.trim();
    }

    private static boolean isHttps(String s) {
        try {
            URI u = new URI(s);
            return "https".equalsIgnoreCase(u.getScheme()) && u.getHost() != null && !u.getHost().isBlank();
        } catch (java.net.URISyntaxException e) {
            return false;
        }
    }
}
