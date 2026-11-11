package com.english9x.classroom.klass;

import com.english9x.audit.api.AuditLog;
import com.english9x.billing.api.SeatAssigner;
import com.english9x.classroom.klass.ClassDtos.AddClassMembersResponse;
import com.english9x.classroom.klass.ClassDtos.AssignClassCoursesResponse;
import com.english9x.classroom.klass.ClassDtos.ClassMemberView;
import com.english9x.classroom.klass.ClassDtos.ClassView;
import com.english9x.classroom.klass.ClassProblems.FieldProblem;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Classes (a centre's) and groups (an enterprise's). Creating is the ORG_ADMIN's; the roster is the ORG_ADMIN's and the class's teacher's; giving the class a
 * course is the ORG_ADMIN's (it spends the organisation's seats) and goes through the P5.3 seat path, one person at a time, all or none.
 */
@Service
public class ClassService {

    private final ClassRepository repo;
    private final ClassAccess access;
    private final AuditLog audit;
    private final SeatAssigner seats;

    ClassService(ClassRepository repo, ClassAccess access, AuditLog audit, SeatAssigner seats) {
        this.repo = repo;
        this.access = access;
        this.audit = audit;
        this.seats = seats;
    }

    @Transactional
    public ClassView create(UUID orgId, String name, UUID teacherId) {
        access.orgAdmin(orgId);
        String type = repo.orgType(orgId).orElseThrow(ClassProblems::notFound);
        String clean = name.strip();
        if (clean.isEmpty()) {
            throw ClassProblems.unprocessable("invalid-class", "Invalid class", "name", "Required", "give the class a name");
        }
        if ("ENTERPRISE".equals(type)) {
            if (teacherId != null) {
                throw ClassProblems.unprocessable("group-has-no-teacher", "A group has no teacher", "teacherId", "NotAllowed", "an enterprise's group has no teacher");
            }
        } else {
            if (teacherId == null) {
                throw ClassProblems.unprocessable("teacher-required", "A class needs a teacher", "teacherId", "Required", "choose the teacher of the class");
            }
            if (!repo.isTeacher(orgId, teacherId)) {
                throw ClassProblems.unprocessable("not-a-teacher", "Not a teacher", "teacherId", "NotTeacher", "the teacher must hold the TEACHER role in this organisation");
            }
        }
        UUID id = repo.insert(orgId, clean, teacherId);
        Map<String, Object> detail = new LinkedHashMap<>();
        detail.put("name", clean);
        detail.put("teacherId", teacherId == null ? null : teacherId.toString());
        audit.record("class.create", "class", id, detail);
        return repo.find(orgId, id).orElseThrow();
    }

    @Transactional(readOnly = true)
    public List<ClassView> list(UUID orgId) {
        access.inContext(orgId);
        return repo.list(orgId);
    }

    @Transactional(readOnly = true)
    public ClassView get(UUID orgId, UUID classId) {
        access.inContext(orgId);
        return repo.find(orgId, classId).orElseThrow(ClassProblems::notFound);
    }

    @Transactional(readOnly = true)
    public List<ClassMemberView> members(UUID orgId, UUID classId) {
        access.managed(orgId, classId);
        return repo.members(classId);
    }

    @Transactional
    public AddClassMembersResponse addMembers(UUID orgId, UUID classId, List<UUID> userIds) {
        access.managed(orgId, classId);
        Set<UUID> unique = new LinkedHashSet<>(userIds);
        Set<UUID> inOrg = new LinkedHashSet<>(repo.orgMembersAmong(orgId, new ArrayList<>(unique)));
        List<FieldProblem> errors = new ArrayList<>();
        for (UUID u : unique) {
            if (!inOrg.contains(u)) {
                errors.add(new FieldProblem("userIds", "NotOrgMember", u + " is not a member of the organisation"));
            }
        }
        if (!errors.isEmpty()) {
            throw ClassProblems.unprocessable("not-an-org-member", "Not a member of the organisation", errors, "only members of the organisation can join a class; nobody was added");
        }
        int added = repo.addMembers(classId, orgId, new ArrayList<>(unique));
        Map<String, Object> detail = new LinkedHashMap<>();
        detail.put("userIds", unique.stream().map(UUID::toString).toList());
        detail.put("added", added);
        audit.record("class.members.add", "class", classId, detail);
        return new AddClassMembersResponse(added);
    }

    @Transactional
    public void removeMember(UUID orgId, UUID classId, UUID userId) {
        access.managed(orgId, classId);
        if (!repo.removeMember(classId, userId)) {
            throw ClassProblems.notFound();
        }
        audit.record("class.members.remove", "class", classId, Map.of("userId", userId.toString()));
    }

    /**
     * Gives every member the seat of the licence with the chosen courses (the P5.3 path). A member who already holds a seat of the licence is left as they
     * are and named in the answer. When the seats run out (or the licence is not usable) the whole request fails and nobody gets a seat.
     */
    @Transactional
    public AssignClassCoursesResponse assignCourses(UUID orgId, UUID classId, List<UUID> courseIds, UUID licenseId) {
        access.orgAdmin(orgId);
        access.managed(orgId, classId);
        List<UUID> courses = new ArrayList<>(new LinkedHashSet<>(courseIds));
        int assigned = 0;
        List<UUID> holding = new ArrayList<>();
        for (UUID member : repo.memberIds(classId)) {
            if (seats.assignUnlessHolding(orgId, licenseId, member, courses)) {
                assigned++;
            } else {
                holding.add(member);
            }
        }
        Map<String, Object> detail = new LinkedHashMap<>();
        detail.put("licenseId", licenseId.toString());
        detail.put("courseIds", courses.stream().map(UUID::toString).toList());
        detail.put("assigned", assigned);
        audit.record("class.courses.assign", "class", classId, detail);
        return new AssignClassCoursesResponse(assigned, holding);
    }
}
