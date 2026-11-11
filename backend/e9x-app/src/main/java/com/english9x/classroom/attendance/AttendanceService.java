package com.english9x.classroom.attendance;

import com.english9x.audit.api.AuditLog;
import com.english9x.classroom.attendance.AttendanceDtos.Mark;
import com.english9x.classroom.attendance.AttendanceDtos.Report;
import com.english9x.classroom.attendance.AttendanceDtos.Sheet;
import com.english9x.classroom.attendance.AttendanceDtos.Status;
import com.english9x.classroom.klass.ClassAccess;
import com.english9x.classroom.klass.ClassProblems;
import java.util.EnumMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Attendance of the members of a class at a session: four states, recorded by the people who manage the class (ORG_ADMIN, its teacher). A mark can be
 * corrected by sending the new one. The rate per learner is worked out by the database when read, over the sessions marked for that learner.
 */
@Service
class AttendanceService {

    /** One request marks at most a classroom-full. */
    static final int MAX_MARKS = 500;

    private final AttendanceRepository repo;
    private final ClassAccess access;
    private final AuditLog audit;

    AttendanceService(AttendanceRepository repo, ClassAccess access, AuditLog audit) {
        this.repo = repo;
        this.access = access;
        this.audit = audit;
    }

    private void sessionOfClass(UUID orgId, UUID classId, UUID sessionId) {
        access.managed(orgId, classId);
        UUID of = repo.classOf(sessionId, orgId).orElseThrow(ClassProblems::notFound);
        if (!of.equals(classId)) {
            throw ClassProblems.notFound();
        }
    }

    @Transactional
    Sheet mark(UUID orgId, UUID classId, UUID sessionId, List<Mark> marks) {
        sessionOfClass(orgId, classId, sessionId);
        if (marks.isEmpty() || marks.size() > MAX_MARKS) {
            throw ClassProblems.unprocessable("invalid-attendance", "Invalid attendance", "body", "Size", "send between 1 and " + MAX_MARKS + " marks");
        }
        Set<UUID> members = new HashSet<>(repo.memberIds(classId));
        for (Mark m : marks) {
            if (!members.contains(m.userId())) {
                throw ClassProblems.unprocessable("not-a-member", "Not a member of the class", "userId", "NotAMember", "attendance is recorded for members of the class only");
            }
        }
        UUID by = access.actingUser();
        Map<Status, Integer> counts = new EnumMap<>(Status.class);
        for (Mark m : marks) {
            repo.upsert(sessionId, orgId, m, by);
            counts.merge(m.status(), 1, Integer::sum);
        }
        audit.record("attendance.mark", "class-session", sessionId, Map.of("classId", classId.toString(), "marks", marks.size(),
                "present", counts.getOrDefault(Status.PRESENT, 0), "absent", counts.getOrDefault(Status.ABSENT, 0),
                "late", counts.getOrDefault(Status.LATE, 0), "excused", counts.getOrDefault(Status.EXCUSED, 0)));
        return new Sheet(sessionId, repo.sheet(sessionId));
    }

    @Transactional(readOnly = true)
    Sheet sheet(UUID orgId, UUID classId, UUID sessionId) {
        sessionOfClass(orgId, classId, sessionId);
        return new Sheet(sessionId, repo.sheet(sessionId));
    }

    @Transactional(readOnly = true)
    Report report(UUID orgId, UUID classId) {
        access.managed(orgId, classId);
        return new Report(classId, repo.report(classId));
    }
}
