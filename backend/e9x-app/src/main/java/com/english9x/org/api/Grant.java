package com.english9x.org.api;

import java.util.Optional;
import java.util.UUID;

/**
 * One row of {@code role_grants}: {@code orgId} null = English9x-wide, {@code examCode} null = every exam (only
 * ORG_ADMIN / SYS_ADMIN / LEARNER have that).
 */
public record Grant(Role role, UUID orgId, String examCode) {

    /**
     * Does this grant satisfy "role {@code want}, optionally for {@code exam}" in the context {@code ctxOrg}?
     * Fails closed: a grant counts only in the context it was made for (an English9x grant never carries into an
     * organisation, an organisation's grant never into another or into the personal context), and an exam-less grant
     * covers a named exam only for the roles that apply to every exam.
     */
    public boolean covers(Role want, Optional<String> exam, Optional<UUID> ctxOrg) {
        if (role == null || want == null || role != want) {
            return false;
        }
        if (!java.util.Objects.equals(orgId, ctxOrg.orElse(null))) {
            return false;
        }
        if (exam.isEmpty()) {
            return true;
        }
        if (examCode == null) {
            return role.coversEveryExam();
        }
        return examCode.equals(exam.get());
    }
}
