package com.english9x.org.api;

import java.util.UUID;

/** Is the user a member of the organisation? One query under the user's own row-level-security view. */
public interface OrgMembershipCheck {

    boolean isMember(UUID userId, UUID orgId);
}
