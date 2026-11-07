package com.english9x.org.internal;

import com.english9x.org.api.Authz;
import com.english9x.org.api.CurrentContextHolder;
import com.english9x.org.api.Role;
import com.english9x.org.internal.OrgDtos.OrgView;
import java.util.Optional;
import java.util.UUID;
import org.springframework.stereotype.Component;

/**
 * Who may manage organisation {@code {id}} through {@code /api/orgs/{id}/...}.
 *
 * <ul>
 *   <li>In an organisation context the path must be that organisation (anything else is a 404, whether or not the other
 *       one exists) and the user must be its ORG_ADMIN (403).
 *   <li>In the personal / English9x context only a SYS_ADMIN may, for any organisation that exists (404 otherwise).
 *       Anybody else gets 403 whatever the id: no hint whether it exists.
 * </ul>
 * Every check reads role data; nothing here trusts a UI guard. Call inside a transaction.
 */
@Component
class OrgAccess {

    private final Authz authz;
    private final OrgRepository repo;

    OrgAccess(Authz authz, OrgRepository repo) {
        this.authz = authz;
        this.repo = repo;
    }

    OrgView manageable(UUID orgId) {
        Optional<UUID> ctx = CurrentContextHolder.current().flatMap(CurrentContextHolder.Binding::orgId);
        if (ctx.isPresent()) {
            if (!ctx.get().equals(orgId)) {
                throw OrgProblems.notFound();
            }
            authz.require(Role.ORG_ADMIN, Optional.empty());
        } else {
            authz.require(Role.SYS_ADMIN, Optional.empty());
        }
        return repo.findOrg(orgId).orElseThrow(OrgProblems::notFound);
    }

    /** English9x-level administration: a real SYS_ADMIN in the English9x context. */
    void requireSysAdmin() {
        authz.require(Role.SYS_ADMIN, Optional.empty());
    }

    UUID actingUser() {
        return CurrentContextHolder.current().orElseThrow(() -> new org.springframework.security.access.AccessDeniedException("no user")).userId();
    }
}
