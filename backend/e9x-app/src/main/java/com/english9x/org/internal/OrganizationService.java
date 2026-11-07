package com.english9x.org.internal;

import com.english9x.audit.api.AuditLog;
import com.english9x.org.internal.OrgDtos.AdminOrgView;
import com.english9x.org.internal.OrgDtos.CreateOrganizationRequest;
import com.english9x.org.internal.OrgDtos.MeContext;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
class OrganizationService {

    private final OrgRepository repo;
    private final OrgAccess access;
    private final AuditLog audit;

    OrganizationService(OrgRepository repo, OrgAccess access, AuditLog audit) {
        this.repo = repo;
        this.access = access;
        this.audit = audit;
    }

    /** Only a real SYS_ADMIN (the INSERT policy asks for the flag AND the grant as well). Recorded in the audit log (org.create). */
    @Transactional
    public AdminOrgView create(CreateOrganizationRequest req) {
        access.requireSysAdmin();
        String name = req.name().strip();
        if (name.isEmpty()) {
            throw OrgProblems.validation(List.of(new OrgProblems.FieldProblem("name", null, "NotBlank", "must not be blank")));
        }
        AdminOrgView created = repo.insertOrg(name, req.type());
        audit.record("org.create", "organization", created.id(), Map.of("name", created.name(), "type", created.type().name()));
        return created;
    }

    @Transactional(readOnly = true)
    public List<AdminOrgView> listAll() {
        access.requireSysAdmin();
        return repo.listAllOrgs();
    }

    @Transactional(readOnly = true)
    public MeContext me(Optional<UUID> contextOrg) {
        UUID user = access.actingUser();
        return new MeContext(contextOrg.orElse(null), repo.orgsOf(user), repo.grantsOf(user));
    }
}
