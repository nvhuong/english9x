package com.english9x.catalog;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.english9x.catalog.api.GradingPolicies;
import com.english9x.catalog.api.ResolvedScoreTable;
import com.english9x.catalog.api.ScoreTables;
import com.english9x.domain.grading.GradingMode;
import com.english9x.support.TestPostgres;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

/**
 * The state of the catalog as P2.1 ships it, with nothing dropped: TEST-scope rows are refused for everybody (P2.6 lifts that together
 * with the foreign key to tests, the owner match and the administrator-only writers), the keys carry the owner so one owner's row never
 * blocks another's, and a test version id that nobody can have set an override for resolves to the exam default.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.NONE)
class CatalogTestScopeRefusedIT {

    static final String DB = TestPostgres.createDatabase();
    static final UUID ORG_A = UUID.randomUUID();
    static final UUID ORG_B = UUID.randomUUID();
    static JdbcTemplate su;

    @DynamicPropertySource
    static void database(DynamicPropertyRegistry registry) {
        TestPostgres.connect(registry, DB);
    }

    @Autowired ScoreTables tables;
    @Autowired GradingPolicies policies;

    @BeforeAll
    static void fixtures(@Autowired JdbcTemplate migrated) {
        su = new JdbcTemplate(TestPostgres.superuser(DB));
        su.update("insert into organizations(id,name,type) values (?,'A','CENTER'),(?,'B','CENTER')", ORG_A, ORG_B);
        UUID t = UUID.randomUUID();
        su.update("insert into score_tables(id,exam_code,skill,scope) values (?,'IELTS','LISTENING','EXAM')", t);
        su.update("insert into score_table_versions(score_table_id,exam_code,version_no,status,entries) values (?,'IELTS',1,'PUBLISHED','[{\"raw\":0,\"scaled\":1}]'::jsonb)", t);
    }

    @Test
    void noTestScopeScoreTableCanBeInsertedByAnyone() {
        for (UUID owner : new UUID[] {null, ORG_A}) {
            DataIntegrityViolationException e = assertThrows(DataIntegrityViolationException.class, () -> su.update(
                    "insert into score_tables(owner_org_id,exam_code,skill,scope,test_id) values (?,'IELTS','READING_AC','TEST',gen_random_uuid())", owner));
            assertTrue(e.getMessage().contains("score_tables_test_scope_refused_ck"), e.getMessage());
        }
    }

    @Test
    void noTestScopeGradingPolicyCanBeInsertedByAnyone() {
        for (UUID owner : new UUID[] {null, ORG_A}) {
            DataIntegrityViolationException e = assertThrows(DataIntegrityViolationException.class, () -> su.update(
                    "insert into grading_policies(owner_org_id,exam_code,scope,test_id,mode) values (?,'IELTS','TEST',gen_random_uuid(),'AI')", owner));
            assertTrue(e.getMessage().contains("grading_policies_test_scope_refused_ck"), e.getMessage());
        }
    }

    @Test
    void aTestVersionHasNoOverrideBeforeP26AndTheDefaultsApply() {
        ResolvedScoreTable r = tables.resolve("IELTS", "LISTENING", Optional.of(UUID.randomUUID()));
        assertFalse(r.testOverride());
        assertEquals(GradingMode.AI, policies.resolve("IELTS", Optional.empty(), Optional.of(UUID.randomUUID())));
    }

    @Test
    void everyKeyCarriesTheOwnerSoOneOwnersRowNeverBlocksAnothers() {
        for (String index : List.of("score_tables_exam_default_uq", "score_tables_test_override_uq", "grading_policies_exam_uq",
                "grading_policies_org_uq", "grading_policies_test_uq")) {
            String def = su.queryForObject("select indexdef from pg_indexes where indexname = ?", String.class, index);
            assertTrue(def.contains("owner_org_id"), index + ": " + def);
        }
        // org A and org B each hold an ORG policy for the same exam; neither sees (or collides with) the other's
        su.update("insert into grading_policies(owner_org_id,exam_code,scope,mode) values (?,'TOEIC','ORG','AI')", ORG_A);
        su.update("insert into grading_policies(owner_org_id,exam_code,scope,mode) values (?,'TOEIC','ORG','BOTH')", ORG_B);
        assertThrows(DuplicateKeyException.class, () -> su.update("insert into grading_policies(owner_org_id,exam_code,scope,mode) values (?,'TOEIC','ORG','AI')", ORG_A));
        // English9x's exam default is one per exam (a NULL owner counts as a value)
        su.update("insert into grading_policies(owner_org_id,exam_code,scope,mode) values (NULL,'TOEIC','EXAM','AI')");
        assertThrows(DuplicateKeyException.class, () -> su.update("insert into grading_policies(owner_org_id,exam_code,scope,mode) values (NULL,'TOEIC','EXAM','BOTH')"));
    }
}
