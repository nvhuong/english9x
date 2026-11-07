package com.english9x.org.internal;

import com.english9x.org.api.Role;
import com.english9x.org.internal.OrgDtos.OrgType;
import com.english9x.org.internal.OrgDtos.RoleSpec;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;

/**
 * What a list of roles may contain. The database enforces the same rules (CHECKs and triggers on role_grants and
 * invitations); this class gives the caller a precise 400 / 409 before the database would refuse with a bare error.
 *
 * <ul>
 *   <li>English9x ({@code orgType} empty): AUTHOR, REVIEWER, TEACHER (each for an exam) and SYS_ADMIN.
 *   <li>An organisation: LEARNER, ORG_ADMIN and, for a CENTER only, AUTHOR, REVIEWER, TEACHER (each for an exam).
 *       ENTERPRISE with AUTHOR / REVIEWER / TEACHER is a 409 (spec §4), not a 400: the request is well formed, the
 *       organisation's type is what forbids it.
 *   <li>Exam codes: v1 offers IELTS and TOEIC. Assumption: P2.1 (catalogue) replaces this constant with the {@code exams} table.
 * </ul>
 */
final class RoleRules {

    /** v1 exams; replaced by the catalogue's exams in P2.1. */
    static final Set<String> KNOWN_EXAMS = Set.of("IELTS", "TOEIC");

    private RoleRules() {}

    /** The roles without duplicates, in order. Throws 400 (shape) or 409 (ENTERPRISE) problems. */
    static List<RoleSpec> check(Optional<OrgType> orgType, List<RoleSpec> roles) {
        List<OrgProblems.FieldProblem> shape = new ArrayList<>();
        List<OrgProblems.FieldProblem> forbidden = new ArrayList<>();
        Set<RoleSpec> unique = new LinkedHashSet<>();
        for (int i = 0; i < roles.size(); i++) {
            RoleSpec spec = roles.get(i);
            String at = "roles[" + i + "]";
            Role role = spec.role();
            String exam = spec.examCode() == null || spec.examCode().isBlank() ? null : spec.examCode().strip();
            boolean perExam = role == Role.AUTHOR || role == Role.REVIEWER || role == Role.TEACHER;
            if (perExam && exam == null) {
                shape.add(new OrgProblems.FieldProblem(at + ".examCode", i, "ExamRequired", "This role is for one exam: choose it."));
            } else if (perExam && !KNOWN_EXAMS.contains(exam)) {
                shape.add(new OrgProblems.FieldProblem(at + ".examCode", i, "UnknownExam", "Unknown exam."));
            } else if (!perExam && exam != null) {
                shape.add(new OrgProblems.FieldProblem(at + ".examCode", i, "ExamNotAllowed", "This role applies to every exam."));
            }
            boolean english9x = orgType.isEmpty();
            boolean here = english9x
                    ? (perExam || role == Role.SYS_ADMIN)
                    : (role != Role.SYS_ADMIN);
            if (!here) {
                shape.add(new OrgProblems.FieldProblem(at + ".role", i, "RoleNotAllowedHere", "This role cannot be given here."));
            } else if (orgType.isPresent() && orgType.get() == OrgType.ENTERPRISE && role.forbiddenInEnterprise()) {
                forbidden.add(new OrgProblems.FieldProblem(at + ".role", i, "RoleNotAllowedForEnterprise", "An enterprise has no authors, reviewers or teachers."));
            }
            unique.add(new RoleSpec(role, perExam ? exam : null));
        }
        if (!shape.isEmpty()) {
            throw OrgProblems.validation(shape);
        }
        if (!forbidden.isEmpty()) {
            throw OrgProblems.roleNotAllowed(forbidden);
        }
        return List.copyOf(unique);
    }
}
