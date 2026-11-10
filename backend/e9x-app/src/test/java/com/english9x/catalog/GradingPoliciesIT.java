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
    // the override belongs to a TEST; a test has several versions (spec 10.3 "theo đề")
    static final UUID T_A = UUID.randomUUID();        // a test of org A with its own mode
    static final UUID T_E9 = UUID.randomUUID();       // a test of English9x with its own mode
    static final UUID TEST_A = UUID.randomUUID();     // version 1 of T_A
    static final UUID TEST_A_V2 = UUID.randomUUID();  // version 2 of T_A, published later
    static final UUID TEST_E9 = UUID.randomUUID();    // a version of T_E9
    static final UUID TEST_PLAIN = UUID.randomUUID(); // a version of a test with no policy
    static JdbcTemplate su;

    @DynamicPropertySource
    static void database(DynamicPropertyRegistry registry) {
        TestPostgres.connect(registry, DB);
        registry.add("e9x.grading.default-mode", () -> "TEACHER");
    }

    @Autowired GradingPolicies policies;

    static void policy(UUID owner, String exam, String scope, UUID test, String mode) {
        su.update("insert into grading_policies(owner_org_id,exam_code,scope,test_id,mode) values (?,?,?,?,?)", owner, exam, scope, test, mode);
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
        // real tests (P2.6): a TEST policy belongs to a test that exists, of the same owner; TestLookup reads test_versions
        UUID by = com.english9x.content.test.TestRows.author(su);
        UUID plain = UUID.randomUUID();
        for (Object[] t : new Object[][] {{T_A, ORG_A}, {T_E9, null}, {plain, null}}) {
            su.update("insert into tests(id,owner_org_id,exam_code,created_by) values (?,?,'IELTS',?)", t[0], t[1], by);
        }
        for (Object[] v : new Object[][] {{TEST_A, T_A, ORG_A, 1}, {TEST_A_V2, T_A, ORG_A, 2}, {TEST_E9, T_E9, null, 1}, {TEST_PLAIN, plain, null, 1}}) {
            su.update("insert into test_versions(id,test_id,owner_org_id,exam_code,version_no,status,title,created_by) values (?,?,?,'IELTS',?,'PUBLISHED','t',?)",
                    v[0], v[1], v[2], v[3], by);
        }
        policy(ORG_A, "IELTS", "TEST", T_A, "BOTH");
        policy(null, "IELTS", "TEST", T_E9, "BOTH");
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
    void aNewVersionOfTheSameTestKeepsItsPolicy() {
        assertEquals(GradingMode.BOTH, resolve(USER_A, ORG_A, "IELTS", ORG_A, TEST_A));
        assertEquals(GradingMode.BOTH, resolve(USER_A, ORG_A, "IELTS", ORG_A, TEST_A_V2), "version 2 of the test is the same test: same policy");
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
