package com.english9x.course.internal;

import com.english9x.audit.api.AuditLog;
import com.english9x.course.internal.CourseRepository.Header;
import com.english9x.course.internal.CourseRepository.QueueRow;
import com.english9x.org.api.Authz;
import com.english9x.org.api.Role;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * The English9x side of the shared catalogue (F21, spec §14): the queue of PENDING centre courses and the decision. Only an English9x REVIEWER of the exam, in
 * the English9x context, may act — Row-Level Security (courses_share_decide / courses_select_shared_review) is the real gate, this gives the precise 403/404.
 *
 * <p>Ruling (i): a SYS_ADMIN does not approve by being a SYS_ADMIN — approval is the REVIEWER role's (spec §4 keeps roles separate, P1.6 precedent). A bare
 * SYS_ADMIN neither sees the queue (the reviewers' SELECT policy) nor passes {@code authz.require(REVIEWER, exam)}. Ruling (ii): a decline is REJECTED, a real
 * status, so a rejected course is not confused with one never offered, and the owner can re-request it.
 */
@Service
class SharedCatalogService {

    private final Authz authz;
    private final CourseRepository repository;
    private final AuditLog audit;

    SharedCatalogService(Authz authz, CourseRepository repository, AuditLog audit) {
        this.authz = authz;
        this.repository = repository;
        this.audit = audit;
    }

    @Transactional(readOnly = true)
    List<QueueRow> queue(String examCode) {
        if (examCode != null && !examCode.isBlank()) {
            authz.require(Role.REVIEWER, Optional.of(examCode.strip()));
        } else if (!authz.has(Role.REVIEWER, Optional.empty())) {
            throw new AccessDeniedException("not an English9x reviewer");
        }
        return repository.sharedQueue(examCode == null || examCode.isBlank() ? null : examCode.strip());
    }

    @Transactional
    String approve(UUID courseId) {
        return decide(courseId, "course.share-approve", "APPROVED", repository::approveShare);
    }

    @Transactional
    String reject(UUID courseId) {
        return decide(courseId, "course.share-reject", "REJECTED", repository::rejectShare);
    }

    private String decide(UUID courseId, String action, String result, java.util.function.ToIntFunction<UUID> move) {
        Header header = repository.header(courseId).orElseThrow(CourseProblems::notFound);
        authz.require(Role.REVIEWER, Optional.of(header.examCode()));
        if (move.applyAsInt(courseId) != 1) {
            throw CourseProblems.shareConflict();
        }
        // ids only, no personal data: the owning centre's id and the exam
        audit.record(action, "course", courseId, Map.of("ownerOrgId", String.valueOf(header.ownerOrgId()), "exam", header.examCode()));
        return result;
    }
}
