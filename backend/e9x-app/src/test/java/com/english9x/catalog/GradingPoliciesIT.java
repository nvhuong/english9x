package com.english9x.catalog;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import com.english9x.catalog.api.GradingPolicies;
import com.english9x.domain.grading.GradingMode;
import com.english9x.org.api.CurrentContextHolder;
import com.english9x.support.TestPostgres;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

/** GradingPolicies#resolve: test beats organisation beats exam beats the configured default, and an organisation's policy never reaches another. */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.NONE)
class GradingPoliciesIT {

    static final String DB = TestPostgres.createDatabase();
    static final UUID ORG_A = UUID.randomUUID();
    static final UUID ORG_B = UUID.randomUUID();
    static final UUID ORG_C = UUID.randomUUID();      // has no policy of its own
    static final UUID USER_A = UUID.randomUUID();
    static final UUID USER_B = UUID.randomUUID();
    static final UUID USER_C = UUID.randomUUID();
    static final UUID USER_P = UUID.randomUUID();
    static final UUID TEST_A = UUID.randomUUID();     // a test version of org A with its own mode
    static final UUID TEST_E9 = UUID.randomUUID();    // a test version of English9x with its own mode
    static final UUID TEST_PLAIN = UUID.randomUUID();
    static JdbcTemplate su;

    @DynamicPropertySource
    static void database(DynamicPropertyRegistry registry) {
        TestPostgres.connect(registry, DB);
        registry.add("e9x.grading.default-mode", () -> "TEACHER");
    }

    @Autowired GradingPolicies policies;

    static void policy(UUID owner, String exam, String scope, UUID test, String mode) {
        su.update("insert into grading_policies(owner_org_id,exam_code,scope,test_version_id,mode) values (?,?,?,?,?)", owner, exam, scope, test, mode);
    }

    @BeforeAll
    static void fixtures(@Autowired JdbcTemplate migrated) {   // the parameter makes Spring boot (and Flyway migrate) first
        su = new JdbcTemplate(TestPostgres.superuser(DB));
        su.update("insert into organizations(id,name,type) values (?,'A','CENTER'),(?,'B','CENTER'),(?,'C','CENTER')", ORG_A, ORG_B, ORG_C);
        for (UUID u : new UUID[] {USER_A, USER_B, USER_C, USER_P}) {
            su.update("insert into users(id,email) values (?,?)", u, u + "@x.test");
        }
        su.update("insert into org_memberships(org_id,user_id) values (?,?),(?,?),(?,?)", ORG_A, USER_A, ORG_B, USER_B, ORG_C, USER_C);
        policy(null, "IELTS", "EXAM", null, "AI");
        policy(ORG_A, "IELTS", "ORG", null, "TEACHER");
        policy(ORG_B, "IELTS", "ORG", null, "BOTH");
        policy(ORG_A, "IELTS", "TEST", TEST_A, "BOTH");
        policy(null, "IELTS", "TEST", TEST_E9, "BOTH");
    }

    private GradingMode resolve(UUID user, UUID boundOrg, String exam, UUID orgParam, UUID test) {
        try (CurrentContextHolder.Scope ignored = CurrentContextHolder.bind(user, boundOrg)) {
            return policies.resolve(exam, Optional.ofNullable(orgParam), Optional.ofNullable(test));
        }
    }

    @Test
    void precedenceTestOrgExam() {
        assertEquals(GradingMode.BOTH, resolve(USER_A, ORG_A, "IELTS", ORG_A, TEST_A), "the test's mode wins over the organisation's TEACHER");
        assertEquals(GradingMode.TEACHER, resolve(USER_A, ORG_A, "IELTS", ORG_A, TEST_PLAIN), "no policy for that test: the organisation's");
        assertEquals(GradingMode.TEACHER, resolve(USER_A, ORG_A, "IELTS", ORG_A, null), "no test: the organisation's");
        assertEquals(GradingMode.AI, resolve(USER_P, null, "IELTS", null, null), "no organisation, no test: the exam's");
        assertEquals(GradingMode.AI, resolve(USER_C, ORG_C, "IELTS", ORG_C, null), "an organisation without a policy gets the exam's");
        assertEquals(GradingMode.BOTH, resolve(USER_P, null, "IELTS", null, TEST_E9), "a test of English9x, for anybody");
        assertEquals(GradingMode.BOTH, resolve(USER_C, ORG_C, "IELTS", ORG_C, TEST_E9), "also for an organisation's learner, which has no policy of its own");
    }

    @Test
    void anOrganisationsPolicyNeverAffectsAnotherOrganisation() {
        // org B's mode is BOTH; org A's learner asking with B's id, or B's learner asking for A, gets the exam's mode, never the other's
        assertEquals(GradingMode.AI, resolve(USER_A, ORG_A, "IELTS", ORG_B, null));
        assertEquals(GradingMode.AI, resolve(USER_B, ORG_B, "IELTS", ORG_A, null));
        assertEquals(GradingMode.BOTH, resolve(USER_B, ORG_B, "IELTS", ORG_B, null));
    }

    @Test
    void aTestPolicyOfAnotherOrganisationIsNotSeen() {
        // for org B (policy BOTH) the answer would be BOTH either way; use org C (exam AI) to see the test policy of A is not applied
        assertEquals(GradingMode.AI, resolve(USER_C, ORG_C, "IELTS", ORG_C, TEST_A));
        assertEquals(GradingMode.AI, resolve(USER_P, null, "IELTS", null, TEST_A));
    }

    @Test
    void noExamPolicyFallsBackToTheConfiguredDefault() {
        assertEquals(GradingMode.TEACHER, resolve(USER_P, null, "TOEIC", null, null), "TOEIC has no policy row: e9x.grading.default-mode (set to TEACHER in this test)");
        assertEquals(GradingMode.AI, resolve(USER_P, null, "IELTS", null, null), "IELTS has a row, which wins over the default");
    }

    @Test
    void anUnknownExamIsRefused() {
        assertThrows(IllegalArgumentException.class, () -> resolve(USER_P, null, "NOPE", null, null));
    }
}
