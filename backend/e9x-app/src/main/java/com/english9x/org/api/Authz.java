package com.english9x.org.api;

import java.util.Optional;

/**
 * Role checks for the acting user in the current context. Always asks the database (role data), never a client claim and
 * never a UI guard. Reads inside a transaction (it opens one when the caller has none), so it answers the same for
 * every caller; with no user bound it denies.
 *
 * <p>Strict: a role does not imply another (a SYS_ADMIN is not an AUTHOR, an ORG_ADMIN is not a TEACHER).
 */
public interface Authz {

    /**
     * @param examCode the exam the action concerns, or empty when any exam will do (the user must still hold the role)
     * @throws org.springframework.security.access.AccessDeniedException when the acting user does not hold the role in the
     *     current context (rendered as 403 problem+json)
     */
    void require(Role role, Optional<String> examCode);

    /** Same test without throwing. */
    boolean has(Role role, Optional<String> examCode);

    /** The reviewer of a version may never be its author (spec §4). Always false: there is no exception to ask for. */
    boolean canReviewOwnVersion();
}
