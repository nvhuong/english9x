package com.english9x.org;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.english9x.org.api.Authz;
import com.english9x.org.api.CurrentContext;
import com.english9x.org.api.CurrentContextHolder;
import com.english9x.org.api.Role;
import com.english9x.support.TestPostgres;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

/** {@link Authz} against the real database: role data, the current context, no transaction needed by the caller. */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.NONE)
class AuthzIT {

    static final String DB = TestPostgres.createDatabase();
    static final UUID ORG_A = UUID.randomUUID();
    static final UUID ORG_B = UUID.randomUUID();
    static final UUID AUTHOR = UUID.randomUUID();
    static final UUID ADMIN_A = UUID.randomUUID();
    static final UUID TEACHER_A = UUID.randomUUID();
    static final UUID SYS = UUID.randomUUID();
    static final UUID NOBODY = UUID.randomUUID();
    static final UUID AUTHOR_IN_A = UUID.randomUUID();

    @DynamicPropertySource
    static void database(DynamicPropertyRegistry registry) {
        TestPostgres.connect(registry, DB);
    }

    @Autowired
    Authz authz;

    @Autowired
    PlatformTransactionManager tm;

    @Autowired
    CurrentContext context;

    @BeforeAll
    static void fixtures(@Autowired JdbcTemplate migrated) {
        JdbcTemplate su = new JdbcTemplate(TestPostgres.superuser(DB));
        su.update("insert into organizations(id,name,type) values (?,'A','CENTER'),(?,'B','CENTER')", ORG_A, ORG_B);
        for (UUID u : new UUID[] {AUTHOR, ADMIN_A, TEACHER_A, SYS, NOBODY, AUTHOR_IN_A}) {
            su.update("insert into users(id,email) values (?,?)", u, u + "@x.test");
        }
        su.update("insert into org_memberships(org_id,user_id) values (?,?),(?,?),(?,?)", ORG_A, ADMIN_A, ORG_A, TEACHER_A, ORG_A, AUTHOR_IN_A);
        su.update("insert into role_grants(user_id,org_id,exam_code,role) values (?,null,'IELTS','AUTHOR')", AUTHOR);
        su.update("insert into role_grants(user_id,org_id,exam_code,role) values (?,?,null,'ORG_ADMIN')", ADMIN_A, ORG_A);
        su.update("insert into role_grants(user_id,org_id,exam_code,role) values (?,?,'IELTS','TEACHER')", TEACHER_A, ORG_A);
        su.update("insert into role_grants(user_id,org_id,exam_code,role) values (?,null,null,'SYS_ADMIN')", SYS);
        su.update("insert into role_grants(user_id,org_id,exam_code,role) values (?,null,'IELTS','AUTHOR')", AUTHOR_IN_A);
    }

    private <T> T as(UUID user, UUID org, java.util.function.Supplier<T> body) {
        try (var scope = CurrentContextHolder.bind(user, org)) {
            return body.get();
        }
    }

    @Test
    void examScopedRoleDeniesOtherExam() {
        as(AUTHOR, null, () -> {
            assertDoesNotThrow(() -> authz.require(Role.AUTHOR, Optional.of("IELTS")));
            assertThrows(AccessDeniedException.class, () -> authz.require(Role.AUTHOR, Optional.of("TOEIC")));
            assertThrows(AccessDeniedException.class, () -> authz.require(Role.REVIEWER, Optional.of("IELTS")));
            assertDoesNotThrow(() -> authz.require(Role.AUTHOR, Optional.empty()));
            return null;
        });
    }

    @Test
    void worksWithoutATransactionOfTheCallersAndInsideOne() {
        as(AUTHOR, null, () -> {
            assertFalse(org.springframework.transaction.support.TransactionSynchronizationManager.isActualTransactionActive());
            assertTrue(authz.has(Role.AUTHOR, Optional.of("IELTS")), "Authz opens its own transaction: the answer does not depend on the caller having one");
            return null;
        });
        as(AUTHOR, null, () -> new TransactionTemplate(tm).execute(s -> {
            assertTrue(authz.has(Role.AUTHOR, Optional.of("IELTS")));
            assertFalse(authz.has(Role.AUTHOR, Optional.of("TOEIC")));
            return null;
        }));
    }

    @Test
    void theContextFlagsAreFalseOutsideATransactionSoAuthzNeverRelaysOnThem() {
        as(ADMIN_A, ORG_A, () -> {
            assertFalse(context.isOrgAdmin(), "the P1.3 flag reads false outside a transaction");
            assertTrue(authz.has(Role.ORG_ADMIN, Optional.empty()), "but Authz still answers from role data");
            return null;
        });
    }

    @Test
    void anOrganisationRoleCountsInThatOrganisationOnly() {
        as(ADMIN_A, ORG_A, () -> {
            assertTrue(authz.has(Role.ORG_ADMIN, Optional.empty()));
            assertTrue(authz.has(Role.ORG_ADMIN, Optional.of("TOEIC")), "ORG_ADMIN covers every exam");
            assertFalse(authz.has(Role.TEACHER, Optional.of("IELTS")), "being the admin does not make you a teacher");
            return null;
        });
        as(ADMIN_A, null, () -> {
            assertFalse(authz.has(Role.ORG_ADMIN, Optional.empty()), "personal context: no organisation power");
            return null;
        });
        as(TEACHER_A, ORG_A, () -> {
            assertTrue(authz.has(Role.TEACHER, Optional.of("IELTS")));
            assertFalse(authz.has(Role.TEACHER, Optional.of("TOEIC")));
            return null;
        });
    }

    @Test
    void anEnglish9xRoleDoesNotCarryIntoAnOrganisationAndASysAdminIsEnglish9xOnly() {
        as(AUTHOR_IN_A, ORG_A, () -> {
            assertFalse(authz.has(Role.AUTHOR, Optional.of("IELTS")), "AUTHOR of English9x, sitting in organisation A as a member");
            return null;
        });
        as(AUTHOR_IN_A, null, () -> {
            assertTrue(authz.has(Role.AUTHOR, Optional.of("IELTS")));
            return null;
        });
        as(SYS, null, () -> {
            assertTrue(authz.has(Role.SYS_ADMIN, Optional.empty()));
            assertFalse(authz.has(Role.AUTHOR, Optional.empty()), "SYS_ADMIN is not an AUTHOR");
            return null;
        });
    }

    @Test
    void deniesWhenNoUserIsBoundOrTheUserHoldsNothing() {
        assertThrows(AccessDeniedException.class, () -> authz.require(Role.LEARNER, Optional.empty()));
        as(NOBODY, null, () -> {
            for (Role r : Role.values()) {
                assertFalse(authz.has(r, Optional.empty()), r.name());
            }
            return null;
        });
    }

    @Test
    void readsTheCurrentStateOfTheRoleDataEveryTime() {
        UUID u = UUID.randomUUID();
        JdbcTemplate su = new JdbcTemplate(TestPostgres.superuser(DB));
        su.update("insert into users(id,email) values (?,?)", u, u + "@x.test");
        su.update("insert into role_grants(user_id,org_id,exam_code,role) values (?,null,'TOEIC','REVIEWER')", u);
        as(u, null, () -> {
            assertTrue(authz.has(Role.REVIEWER, Optional.of("TOEIC")));
            su.update("delete from role_grants where user_id = ?", u);
            assertFalse(authz.has(Role.REVIEWER, Optional.of("TOEIC")), "a revoked grant stops counting at once");
            return null;
        });
    }

    @Test
    void aReviewerNeverReviewsTheirOwnVersion() {
        assertFalse(authz.canReviewOwnVersion());
        as(SYS, null, () -> {
            assertFalse(authz.canReviewOwnVersion(), "not even the SYS_ADMIN");
            return null;
        });
    }
}
