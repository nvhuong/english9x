package com.english9x.org.internal;

import com.english9x.org.api.Role;
import com.english9x.org.internal.OrgDtos.MemberView;
import com.english9x.org.internal.OrgDtos.MembersView;
import com.english9x.org.internal.OrgDtos.OrgView;
import com.english9x.org.internal.OrgDtos.RoleSpec;
import com.english9x.org.internal.OrgDtos.SetRolesRequest;
import java.util.List;
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

    RoleGrantService(OrgRepository repo, OrgAccess access) {
        this.repo = repo;
        this.access = access;
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
        List<RoleSpec> wanted = RoleRules.check(Optional.of(org.type()), req.roles());
        if (!repo.isMember(orgId, req.userId())) {
            throw OrgProblems.notFound(); // grants never exist without a membership; invitations create both
        }
        repo.lockOrg(orgId);
        boolean hadAdmin = repo.rolesOf(orgId, req.userId()).stream().anyMatch(r -> r.role() == Role.ORG_ADMIN);
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
        // Audit (P1.9) must record this change: actor, organisation, target, old and new roles.
        return repo.members(orgId).stream().filter(m -> m.userId().equals(req.userId())).findFirst()
                .orElseThrow(OrgProblems::notFound);
    }
}
