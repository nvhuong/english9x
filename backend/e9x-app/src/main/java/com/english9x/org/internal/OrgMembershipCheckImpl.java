package com.english9x.org.internal;

import com.english9x.org.api.CurrentContextHolder;
import com.english9x.org.api.OrgMembershipCheck;
import java.util.UUID;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * One indexed query under the user's own row-level-security view ({@code org_memberships} shows a user their own rows).
 * Runs in the personal context of the user (the request filter binds that before asking); a binding for anybody else
 * answers false.
 */
@Service
class OrgMembershipCheckImpl implements OrgMembershipCheck {

    private final JdbcTemplate jdbc;

    OrgMembershipCheckImpl(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    @Override
    @Transactional(readOnly = true)
    public boolean isMember(UUID userId, UUID orgId) {
        boolean bound = CurrentContextHolder.current().map(b -> b.userId().equals(userId)).orElse(false);
        if (!bound || orgId == null) {
            return false;
        }
        return Boolean.TRUE.equals(jdbc.queryForObject(
                "select exists (select 1 from org_memberships where user_id = ? and org_id = ?)", Boolean.class, userId, orgId));
    }
}
