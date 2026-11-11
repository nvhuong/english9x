package com.english9x.classroom.klass;

import com.english9x.classroom.klass.ClassDtos.ClassView;
import com.english9x.org.api.Authz;
import com.english9x.org.api.CurrentContextHolder;
import com.english9x.org.api.Role;
import java.util.Optional;
import java.util.UUID;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.stereotype.Component;

/**
 * Who may reach organisation {@code {id}}'s classes. The organisation must be the one of the request's context (any other id is 404, whether or not it
 * exists: F08); outside any organisation context there is nothing to reach (403). Role checks read role data; the database repeats them in its policies.
 * Call inside a transaction.
 */
@Component
public class ClassAccess {

    private final Authz authz;
    private final ClassRepository repo;

    ClassAccess(Authz authz, ClassRepository repo) {
        this.authz = authz;
        this.repo = repo;
    }

    /** The organisation of the context must be the path's. */
    public void inContext(UUID orgId) {
        Optional<UUID> ctx = CurrentContextHolder.current().flatMap(CurrentContextHolder.Binding::orgId);
        if (ctx.isEmpty()) {
            throw new AccessDeniedException("an organisation context is needed");
        }
        if (!ctx.get().equals(orgId)) {
            throw ClassProblems.notFound();
        }
    }

    /** The ORG_ADMIN of the context's organisation. */
    public void orgAdmin(UUID orgId) {
        inContext(orgId);
        authz.require(Role.ORG_ADMIN, Optional.empty());
    }

    /** The class, for somebody who may manage it (the ORG_ADMIN, or its teacher): 404 when it is not visible, 403 when it is visible but not theirs to manage. */
    public ClassView managed(UUID orgId, UUID classId) {
        inContext(orgId);
        ClassView c = repo.find(orgId, classId).orElseThrow(ClassProblems::notFound);
        if (!c.canManage()) {
            throw new AccessDeniedException("not the teacher of this class");
        }
        return c;
    }

    public UUID actingUser() {
        return CurrentContextHolder.current().orElseThrow(() -> new AccessDeniedException("no user")).userId();
    }
}
