package com.english9x.classroom.assignment;

import com.english9x.audit.api.AuditLog;
import com.english9x.classroom.assignment.AssignmentDtos.AssignmentView;
import com.english9x.classroom.assignment.AssignmentDtos.MyAssignment;
import com.english9x.classroom.assignment.AssignmentDtos.Report;
import com.english9x.classroom.klass.ClassAccess;
import com.english9x.classroom.klass.ClassProblems;
import com.english9x.classroom.klass.ClassRepository;
import java.time.Clock;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Giving work to a class (a lesson, a chapter or mock test of a course, or an exam) with a due date; seeing who handed in, who was late and who did not.
 * The people who manage the class (ORG_ADMIN, its teacher) give and see; a learner sees their own list. Whether work is done is not stored by this code:
 * a test assignment is marked by {@code AttemptSubmitted} ({@link SubmissionListener}), a lesson assignment is done when the learner completed the lesson;
 * MISSING is "not done and past the due date", worked out when read.
 */
@Service
class AssignmentService {

    private final AssignmentRepository repo;
    private final ClassAccess access;
    private final ClassRepository classes;
    private final AuditLog audit;
    private final Clock clock;
    private final ApplicationEventPublisher events;

    AssignmentService(AssignmentRepository repo, ClassAccess access, ClassRepository classes, AuditLog audit, Clock clock, ApplicationEventPublisher events) {
        this.repo = repo;
        this.access = access;
        this.classes = classes;
        this.audit = audit;
        this.clock = clock;
        this.events = events;
    }

    @Transactional
    AssignmentView create(UUID orgId, UUID classId, UUID lessonId, UUID testVersionId, Instant dueAt) {
        access.managed(orgId, classId);
        if ((lessonId == null) == (testVersionId == null)) {
            throw ClassProblems.unprocessable("invalid-assignment", "Invalid assignment", "lessonId", "OneTarget", "give exactly one of lessonId and testVersionId");
        }
        if (!dueAt.isAfter(clock.instant())) {
            throw ClassProblems.unprocessable("due-in-past", "The due date has passed", "dueAt", "InThePast", "the due date must be in the future");
        }
        if (!repo.targetOk(lessonId, testVersionId, orgId)) {
            throw ClassProblems.unprocessable("assignment-target-unavailable", "Not available", lessonId != null ? "lessonId" : "testVersionId", "NotAvailable",
                    "the lesson or test must be published and available to this organisation");
        }
        UUID id;
        try {
            id = repo.insert(classId, orgId, lessonId, testVersionId, dueAt, access.actingUser());
        } catch (DataIntegrityViolationException e) {
            throw ClassProblems.unprocessable("assignment-target-unavailable", "Not available", lessonId != null ? "lessonId" : "testVersionId", "NotAvailable",
                    "the lesson or test must be published and available to this organisation");
        }
        List<UUID> recipients = repo.memberIds(classId);
        Map<String, Object> detail = new LinkedHashMap<>();
        detail.put("classId", classId.toString());
        detail.put(lessonId != null ? "lessonId" : "testVersionId", (lessonId != null ? lessonId : testVersionId).toString());
        detail.put("dueAt", dueAt.toString());
        audit.record("assignment.create", "assignment", id, detail);
        events.publishEvent(new AssignmentCreated(id, recipients));
        return repo.ofClass(classId, clock.instant()).stream().filter(a -> a.id().equals(id)).findFirst().orElseThrow();
    }

    @Transactional(readOnly = true)
    List<AssignmentView> ofClass(UUID orgId, UUID classId) {
        access.managed(orgId, classId);
        return repo.ofClass(classId, clock.instant());
    }

    @Transactional(readOnly = true)
    Report report(UUID orgId, UUID assignmentId) {
        access.inContext(orgId);
        UUID classId = repo.classOf(assignmentId, orgId).orElseThrow(ClassProblems::notFound);
        access.managed(orgId, classId);
        return new Report(assignmentId, repo.report(assignmentId, clock.instant()));
    }

    /** The learner's own assignments in the organisation of the context (none in the personal context). */
    @Transactional(readOnly = true)
    List<MyAssignment> mine() {
        return repo.mine(clock.instant());
    }
}
