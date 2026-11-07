package com.english9x.identity.api;

import java.util.Optional;
import java.util.UUID;

/**
 * Read access to users for other modules. Runs under the caller's Row-Level Security context: a user sees
 * themself, members of the organisation of the current context, and (for English9x staff) everyone. With no
 * user bound the answer is "nobody" (fail closed).
 */
public interface UserDirectory {

    Optional<UserView> findById(UUID userId);

    /**
     * Guard for actions that need a confirmed mailbox, above all buying a plan (P5.1: call it before creating an
     * order). Unverified users can sign in and learn, but cannot check out.
     *
     * @throws EmailNotVerifiedException when the user is unknown / not visible to the caller, or has not verified
     */
    void requireVerified(UUID userId);
}
