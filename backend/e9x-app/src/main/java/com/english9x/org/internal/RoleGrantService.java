package com.english9x.org.internal;

import com.english9x.audit.api.AuditLog;
import com.english9x.catalog.api.Exams;
import com.english9x.org.api.Role;
import com.english9x.org.internal.OrgDtos.MemberView;
import com.english9x.org.internal.OrgDtos.MembersView;
import com.english9x.org.internal.OrgDtos.OrgView;
import com.english9x.org.internal.OrgDtos.RoleSpec;
import com.english9x.org.internal.OrgDtos.SetRolesRequest;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** Reading and replacing the grants of the members of one organisation. Membership is never created here: invitations do both. */
@Service
class RoleGrantService {

    private final OrgRepository repo;
    private final OrgAccess access;
    private final AuditLog audit;
    private final Exams exams;

    RoleGrantService(OrgRepository repo, OrgAccess access, AuditLog audit, Exams exams) {
        this.repo = repo;
        this.access = access;
        this.audit = audit;
        this.exams = exams;
    }

    @Transactional(readOnly = true)
    public MembersView list(UUID orgId) {
        access.manageable(orgId);
        return new MembersView(repo.members(orgId));
    }

    @Transactional
    public MemberView set(UUID orgId, SetRolesRequest req) {
        OrgView org = access.manageable(orgId);
        // the same rule the database enforces, with a precise answer: 400 for the shape, 409 for an ENTERPRISE
        List<RoleSpec> wanted = RoleRules.check(Optional.of(org.type()), req.roles(), exams::exists);
        if (!repo.isMember(orgId, req.userId())) {
            throw OrgProblems.notFound(); // grants never exist without a membership; invitations create both
        }
        repo.lockOrg(orgId);
        List<RoleSpec> before = repo.rolesOf(orgId, req.userId());
        boolean hadAdmin = before.stream().anyMatch(r -> r.role() == Role.ORG_ADMIN);
        boolean keepsAdmin = wanted.stream().anyMatch(r -> r.role() == Role.ORG_ADMIN);
        if (hadAdmin && !keepsAdmin && repo.otherAdmins(orgId, req.userId()) == 0) {
            throw OrgProblems.lastOrgAdmin();
        }
        // The checks at the top ran before the wait for the lock. Whoever held it may have demoted this caller; RLS would then turn
        // the insert into an error and the delete into a silent 0 rows. (Two admins demoting each other: the second gets the 409 above.)
        if (!repo.stillManagesCurrentContext()) {
            throw new AccessDeniedException("demoted while waiting");
        }
        repo.setRoles(orgId, req.userId(), wanted);
        // Recorded only when something changed. The organisation is in the detail because a SYS_ADMIN acts from the English9x context, where the
        // record's own context is none.
        List<String> was = before.stream().map(RoleRules::label).sorted().toList();
        List<String> now = wanted.stream().map(RoleRules::label).distinct().sorted().toList();
        if (!was.equals(now)) {
            audit.record("role.change", "user", req.userId(), Map.of("orgId", orgId.toString(), "before", was, "after", now));
        }
        return repo.members(orgId).stream().filter(m -> m.userId().equals(req.userId())).findFirst()
                .orElseThrow(OrgProblems::notFound);
    }
}
