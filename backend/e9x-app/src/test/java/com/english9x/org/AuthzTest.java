package com.english9x.org;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.english9x.org.api.Grant;
import com.english9x.org.api.Role;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.Test;

/** The decision rule behind {@code Authz}: which grants satisfy "role R (for exam X) in context C". */
class AuthzTest {

    static final UUID ORG_A = UUID.randomUUID();
    static final UUID ORG_B = UUID.randomUUID();
    static final Optional<UUID> PERSONAL = Optional.empty();

    private static boolean any(List<Grant> grants, Role role, Optional<String> exam, Optional<UUID> ctx) {
        return grants.stream().anyMatch(g -> g.covers(role, exam, ctx));
    }

    @Test
    void examScopedRoleDeniesOtherExam() {
        List<Grant> authorOfIelts = List.of(new Grant(Role.AUTHOR, null, "IELTS"));
        assertTrue(any(authorOfIelts, Role.AUTHOR, Optional.of("IELTS"), PERSONAL), "the exam the grant names");
        assertFalse(any(authorOfIelts, Role.AUTHOR, Optional.of("TOEIC"), PERSONAL), "another exam is denied");
        assertTrue(any(authorOfIelts, Role.AUTHOR, Optional.empty(), PERSONAL), "no exam named: the role itself is enough");
        assertFalse(any(authorOfIelts, Role.REVIEWER, Optional.of("IELTS"), PERSONAL), "another role is denied");
    }

    @Test
    void anExamlessGrantCoversAnExamOnlyForTheRolesThatApplyToEveryExam() {
        assertTrue(any(List.of(new Grant(Role.ORG_ADMIN, ORG_A, null)), Role.ORG_ADMIN, Optional.of("TOEIC"), Optional.of(ORG_A)));
        assertTrue(any(List.of(new Grant(Role.SYS_ADMIN, null, null)), Role.SYS_ADMIN, Optional.of("IELTS"), PERSONAL));
        // a malformed AUTHOR grant without an exam (the database refuses to store one) must not mean "every exam"
        assertFalse(any(List.of(new Grant(Role.AUTHOR, null, null)), Role.AUTHOR, Optional.of("IELTS"), PERSONAL));
    }

    @Test
    void aGrantCountsOnlyInTheContextItWasMadeFor() {
        List<Grant> grants = List.of(
                new Grant(Role.AUTHOR, null, "IELTS"),
                new Grant(Role.SYS_ADMIN, null, null),
                new Grant(Role.ORG_ADMIN, ORG_A, null),
                new Grant(Role.TEACHER, ORG_A, "IELTS"));
        assertFalse(any(grants, Role.AUTHOR, Optional.of("IELTS"), Optional.of(ORG_A)), "an English9x role does not carry into an organisation");
        assertFalse(any(grants, Role.SYS_ADMIN, Optional.empty(), Optional.of(ORG_A)), "SYS_ADMIN is English9x-only");
        assertFalse(any(grants, Role.ORG_ADMIN, Optional.empty(), PERSONAL), "an org role does not apply in the personal context");
        assertFalse(any(grants, Role.ORG_ADMIN, Optional.empty(), Optional.of(ORG_B)), "nor in another organisation");
        assertTrue(any(grants, Role.ORG_ADMIN, Optional.empty(), Optional.of(ORG_A)));
        assertTrue(any(grants, Role.TEACHER, Optional.of("IELTS"), Optional.of(ORG_A)));
        assertFalse(any(grants, Role.TEACHER, Optional.of("TOEIC"), Optional.of(ORG_A)));
    }

    @Test
    void roleDoesNotImplyAnotherRole() {
        List<Grant> admin = List.of(new Grant(Role.SYS_ADMIN, null, null), new Grant(Role.ORG_ADMIN, ORG_A, null));
        for (Role r : Role.values()) {
            boolean expected = r == Role.SYS_ADMIN;
            assertTrue(any(admin, r, Optional.empty(), PERSONAL) == expected, "SYS_ADMIN in personal context grants only itself, asked " + r);
        }
        assertFalse(any(admin, Role.TEACHER, Optional.of("IELTS"), Optional.of(ORG_A)), "ORG_ADMIN is not a TEACHER");
    }

    @Test
    void nothingGrantedMeansNothingAllowed() {
        assertFalse(any(List.of(), Role.LEARNER, Optional.empty(), PERSONAL));
        assertFalse(new Grant(null, null, null).covers(Role.LEARNER, Optional.empty(), PERSONAL));
    }
}
