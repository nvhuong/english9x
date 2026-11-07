package com.english9x.org.internal;

import com.english9x.org.api.Authz;
import com.english9x.org.api.CurrentContextHolder;
import com.english9x.org.api.Grant;
import com.english9x.org.api.Role;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Reads the acting user's own grants (Row-Level Security lets everyone read their own rows) and applies
 * {@link Grant#covers} in the bound context. {@code @Transactional}: the work happens inside a transaction on every call,
 * so the transaction hook has set the session context for the connection it uses; nothing here depends on the caller
 * having one, and nothing reads the privilege flags of {@code CurrentContext} (false outside a transaction).
 */
@Service
class AuthzImpl implements Authz {

    private final JdbcTemplate jdbc;

    AuthzImpl(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    @Override
    @Transactional(readOnly = true)
    public void require(Role role, Optional<String> examCode) {
        if (!decide(role, examCode)) {
            throw new AccessDeniedException("Access is denied");
        }
    }

    @Override
    @Transactional(readOnly = true)
    public boolean has(Role role, Optional<String> examCode) {
        return decide(role, examCode);
    }

    @Override
    public boolean canReviewOwnVersion() {
        return false;
    }

    private boolean decide(Role role, Optional<String> examCode) {
        Optional<CurrentContextHolder.Binding> binding = CurrentContextHolder.current();
        if (binding.isEmpty() || role == null) {
            return false;
        }
        UUID user = binding.get().userId();
        Optional<UUID> ctxOrg = binding.get().orgId();
        List<Grant> grants = jdbc.query("select role, org_id, exam_code from role_grants where user_id = ?",
                (rs, n) -> new Grant(Role.valueOf(rs.getString("role")), rs.getObject("org_id", UUID.class), rs.getString("exam_code")), user);
        return grants.stream().anyMatch(g -> g.covers(role, examCode, ctxOrg));
    }
}
