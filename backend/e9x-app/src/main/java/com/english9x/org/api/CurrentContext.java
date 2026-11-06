package com.english9x.org.api;

import java.util.Optional;
import java.util.UUID;

/**
 * Who is acting, and in which context: an organisation, or none (the personal / English9x context).
 *
 * <p>The privilege flags are facts derived from role data when the current transaction began; they never come
 * from the client. Outside a transaction they are false (fail closed).
 */
public interface CurrentContext {

    UUID userId();

    Optional<UUID> orgId();

    /** English9x staff (AUTHOR / REVIEWER / TEACHER / SYS_ADMIN without an org) acting in the English9x context. */
    boolean isEnglish9xStaff();

    /** ORG_ADMIN of the organisation of the current context. */
    boolean isOrgAdmin();
}
