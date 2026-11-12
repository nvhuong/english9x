package com.english9x.analytics.reports;

import com.english9x.analytics.progress.LearnerFigures;
import com.english9x.analytics.reports.ReportsDtos.Assignments;
import com.english9x.analytics.reports.ReportsDtos.Attendance;
import com.english9x.analytics.reports.ReportsDtos.ClassReport;
import com.english9x.analytics.reports.ReportsDtos.CourseLine;
import com.english9x.analytics.reports.ReportsDtos.LearnerRow;
import com.english9x.analytics.reports.ReportsDtos.ScoreLine;
import com.english9x.analytics.reports.ReportsDtos.WeakType;
import com.english9x.analytics.reports.ReportsRepository.ClassRow;
import com.english9x.analytics.reports.ReportsRepository.Member;
import com.english9x.analytics.results.LearnerWeakness;
import com.english9x.audit.api.AuditLog;
import com.english9x.org.api.CurrentContextHolder;
import java.time.Clock;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * The report of a class (F49): per learner, the course progress and scores of P4.5 ({@link LearnerFigures}), the assignments and attendance of the classroom, the weakest
 * question types of P4.4 ({@link LearnerWeakness}) and the study minutes. It computes none of those again: it asks the module that owns each and puts the answers side by side.
 *
 * <p><b>Whose.</b> Only the organisation that owns the class, and only the people who manage the class (its ORG_ADMIN, its teacher). The organisation in the path must be the one of the
 * request's context (any other id: 404, whether or not it exists), outside any organisation: 403; a class of another organisation is not visible to the caller (404); a person who
 * is in the organisation but does not manage the class: 403. Every figure is read through functions that repeat those checks in SQL for the organisation of the context, so a bug here
 * cannot widen what is read.
 */
@Service
class ReportsService {

    private final ReportsRepository repo;
    private final LearnerFigures progress;
    private final LearnerWeakness weakness;
    private final AuditLog audit;
    private final Clock clock;
    private final int weakTypes;

    ReportsService(ReportsRepository repo, LearnerFigures progress, LearnerWeakness weakness, AuditLog audit, Clock clock, @Value("${e9x.reports.weak-types:3}") int weakTypes) {
        this.repo = repo;
        this.progress = progress;
        this.weakness = weakness;
        this.audit = audit;
        this.clock = clock;
        this.weakTypes = weakTypes;
    }

    @Transactional(readOnly = true)
    ClassReport report(UUID orgId, UUID classId) {
        return build(orgId, classId);
    }

    /** The same report for the export; the export is recorded in the audit log, so it is a writing transaction. */
    @Transactional
    ClassReport export(UUID orgId, UUID classId) {
        ClassReport r = build(orgId, classId);
        audit.record("report.export", "class", classId, Map.of("learners", r.learners().size(), "format", "xlsx"));
        return r;
    }

    private ClassReport build(UUID orgId, UUID classId) {
        Optional<UUID> ctx = CurrentContextHolder.current().orElseThrow(() -> new AccessDeniedException("no user")).orgId();
        if (ctx.isEmpty()) {
            throw new AccessDeniedException("an organisation context is needed");
        }
        if (!ctx.get().equals(orgId)) {
            throw ReportsProblems.notFound();
        }
        ClassRow cls = repo.find(orgId, classId).orElseThrow(ReportsProblems::notFound);
        if (!cls.canManage()) {
            throw new AccessDeniedException("not the teacher of this class");
        }
        Instant now = clock.instant();
        Map<UUID, Assignments> assignments = repo.assignments(classId, now);
        Map<UUID, Attendance> attendance = repo.attendance(classId);
        List<LearnerRow> rows = new ArrayList<>();
        for (Member m : repo.members(classId)) {
            LearnerFigures.Figures f = progress.of(orgId, m.userId());
            List<WeakType> weak = weakness.worst(orgId, m.userId(), weakTypes).stream()
                    .map(w -> new WeakType(w.typeCode(), w.typeName(), w.correct(), w.total(), w.rate())).toList();
            rows.add(new LearnerRow(m.userId(), m.displayName(), m.email(), f.percent(),
                    f.courses().stream().map(c -> new CourseLine(c.courseId(), c.code(), c.title(), c.percent())).toList(),
                    assignments.getOrDefault(m.userId(), new Assignments(0, 0, 0, 0, 0)), score(f.latestScore()), score(f.bestScore()), weak,
                    attendance.getOrDefault(m.userId(), new Attendance(0, 0, 0, 0, null)), f.studyMinutes()));
        }
        return new ClassReport(cls.id(), cls.name(), cls.kind(), now, rows);
    }

    private static ScoreLine score(LearnerFigures.ScoreLine s) {
        return s == null ? null : new ScoreLine(s.value(), s.kind(), s.examCode());
    }
}
