package com.english9x.org.api;

import java.util.UUID;

/** The user asked to act in an organisation they do not belong to. Request layers map this to 403. */
public class OrgContextDeniedException extends RuntimeException {

    private static final long serialVersionUID = 1L;

    public OrgContextDeniedException(UUID userId, UUID orgId) {
        super("user " + userId + " is not a member of organisation " + orgId);
    }
}
