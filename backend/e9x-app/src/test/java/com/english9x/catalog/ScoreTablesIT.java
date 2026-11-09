package com.english9x.catalog;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.english9x.catalog.api.ResolvedScoreTable;
import com.english9x.catalog.api.ScoreFunction;
import com.english9x.catalog.api.ScoreTableNotFoundException;
import com.english9x.catalog.api.ScoreTables;
import com.english9x.org.api.CurrentContextHolder;
import com.english9x.support.TestPostgres;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

/**
 * ScoreTables#resolve against the real database and the real context binding (RLS): the test's table beats the exam default,
 * Reading AC and GT are two tables, only PUBLISHED versions count (the highest), the version used is returned, and a table of
 * another organisation is never found. Nobody is a mock: a user "is" whoever {@link CurrentContextHolder} is bound to.
 */
@Import(com.english9x.catalog.internal.TestLookupStubConfig.class)
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.NONE)
class ScoreTablesIT {

    static final String DB = TestPostgres.createDatabase();
    static final UUID ORG_A = UUID.randomUUID();
    static final UUID ORG_B = UUID.randomUUID();
    static final UUID LEARNER_A = UUID.randomUUID();
    static final UUID LEARNER_B = UUID.randomUUID();
    static final UUID LEARNER_P = UUID.randomUUID();      // member of nothing: the personal context
    // overrides belong to a TEST; a test has versions (the lookup of P2.6 is stubbed, see TestLookupStubConfig)
    static final UUID T_E9 = UUID.randomUUID();           // a test of English9x with an override
    static final UUID T_A = UUID.randomUUID();            // a test of org A with an override
    static final UUID TEST_E9 = UUID.randomUUID();        // a version of T_E9
    static final UUID TEST_E9_V2 = UUID.randomUUID();     // a later version of T_E9
    static final UUID TEST_A = UUID.randomUUID();         // a version of T_A
    static final UUID TEST_OTHER = UUID.randomUUID();     // a version of a test nobody set a table for
    static JdbcTemplate su;

    @DynamicPropertySource
    static void database(DynamicPropertyRegistry registry) {
        TestPostgres.connect(registry, DB);
    }

    @Autowired ScoreTables tables;

    static String entries(int... scaled) {
        StringBuilder sb = new StringBuilder("[");
        for (int i = 0; i < scaled.length; i++) {
            sb.append(i == 0 ? "" : ",").append("{\"raw\":").append(i).append(",\"scaled\":").append(scaled[i]).append('}');
        }
        return sb.append(']').toString();
    }

    static UUID table(UUID owner, String exam, String skill, String scope, UUID testId) {
        UUID id = UUID.randomUUID();
        su.update("insert into score_tables(id,owner_org_id,exam_code,skill,scope,test_id) values (?,?,?,?,?,?)", id, owner, exam, skill, scope, testId);
        return id;
    }

    static UUID version(UUID table, UUID owner, String exam, int no, String status, String entries) {
        UUID id = UUID.randomUUID();
        su.update("insert into score_table_versions(id,score_table_id,owner_org_id,exam_code,version_no,status,entries) values (?,?,?,?,?,?,?::jsonb)",
                id, table, owner, exam, no, status, entries);
        return id;
    }

    static UUID defaultVersion;
    static UUID overrideE9;
    static UUID overrideA;

    @BeforeAll
    static void fixtures(@Autowired JdbcTemplate migrated) {   // the parameter makes Spring boot (and Flyway migrate) first
        su = new JdbcTemplate(TestPostgres.superuser(DB));
        // P2.6 lifts the refusal of TEST scope (CatalogTestScopeRefusedIT measures it); this throwaway database does it now
        su.execute("alter table score_tables drop constraint score_tables_test_scope_refused_ck");
        com.english9x.catalog.internal.TestLookupStubConfig.version(TEST_E9, T_E9);
        com.english9x.catalog.internal.TestLookupStubConfig.version(TEST_E9_V2, T_E9);
        com.english9x.catalog.internal.TestLookupStubConfig.version(TEST_A, T_A);
        com.english9x.catalog.internal.TestLookupStubConfig.version(TEST_OTHER, UUID.randomUUID());
        su.update("insert into organizations(id,name,type) values (?,'A','CENTER'),(?,'B','CENTER')", ORG_A, ORG_B);
        for (UUID u : new UUID[] {LEARNER_A, LEARNER_B, LEARNER_P}) {
            su.update("insert into users(id,email) values (?,?)", u, u + "@x.test");
        }
        su.update("insert into org_memberships(org_id,user_id) values (?,?),(?,?)", ORG_A, LEARNER_A, ORG_B, LEARNER_B);
        // IELTS Listening: the exam default (raw 0..3 to band), and an override for two test versions
        UUID def = table(null, "IELTS", "LISTENING", "EXAM", null);
        version(def, null, "IELTS", 1, "ARCHIVED", entries(1, 1, 1, 1));
        defaultVersion = version(def, null, "IELTS", 2, "PUBLISHED", entries(0, 3, 5, 9));
        version(def, null, "IELTS", 3, "DRAFT", entries(9, 9, 9, 9));
        version(def, null, "IELTS", 4, "IN_REVIEW", entries(8, 8, 8, 8));
        UUID ov = table(null, "IELTS", "LISTENING", "TEST", T_E9);
        overrideE9 = version(ov, null, "IELTS", 1, "PUBLISHED", entries(2, 4, 6, 8));
        UUID ovA = table(ORG_A, "IELTS", "LISTENING", "TEST", T_A);
        overrideA = version(ovA, ORG_A, "IELTS", 1, "PUBLISHED", entries(1, 2, 3, 4));
        // Reading Academic and General Training: two tables (spec 3.3)
        UUID ac = table(null, "IELTS", "READING_AC", "EXAM", null);
        version(ac, null, "IELTS", 1, "PUBLISHED", entries(0, 4, 6, 9));
        UUID gt = table(null, "IELTS", "READING_GT", "EXAM", null);
        version(gt, null, "IELTS", 1, "PUBLISHED", entries(0, 3, 5, 8));
        // TOEIC Reading: only an archived version, nothing published
        UUID toeic = table(null, "TOEIC", "READING", "EXAM", null);
        version(toeic, null, "TOEIC", 1, "ARCHIVED", entries(5, 10));
    }

    private <T> T as(UUID user, UUID org, java.util.function.Supplier<T> action) {
        try (CurrentContextHolder.Scope ignored = CurrentContextHolder.bind(user, org)) {
            return action.get();
        }
    }

    private ResolvedScoreTable resolve(UUID user, UUID org, String exam, String skill, UUID testVersion) {
        return as(user, org, () -> tables.resolve(exam, skill, Optional.ofNullable(testVersion)));
    }

    @Test
    void testOverrideBeatsExamDefault() {
        ResolvedScoreTable withTest = resolve(LEARNER_P, null, "IELTS", "LISTENING", TEST_E9);
        assertTrue(withTest.testOverride());
        assertEquals(overrideE9, withTest.scoreTableVersionId());
        assertEquals(6.0, withTest.converter().orElseThrow().convert(2));

        ResolvedScoreTable noTest = resolve(LEARNER_P, null, "IELTS", "LISTENING", null);
        assertFalse(noTest.testOverride());
        assertEquals(defaultVersion, noTest.scoreTableVersionId(), "the highest PUBLISHED version of the default, not the draft, the one under review or the archived one");
        assertEquals(5.0, noTest.converter().orElseThrow().convert(2));

        ResolvedScoreTable otherTest = resolve(LEARNER_P, null, "IELTS", "LISTENING", TEST_OTHER);
        assertEquals(defaultVersion, otherTest.scoreTableVersionId(), "a test with no table of its own falls back to the exam default");
        assertFalse(otherTest.testOverride());
    }

    @Test
    void aNewVersionOfTheSameTestKeepsItsOverride() {
        ResolvedScoreTable v1 = resolve(LEARNER_P, null, "IELTS", "LISTENING", TEST_E9);
        ResolvedScoreTable v2 = resolve(LEARNER_P, null, "IELTS", "LISTENING", TEST_E9_V2);
        assertTrue(v2.testOverride(), "the override is the test's, not the version's: republishing the test does not drop it");
        assertEquals(v1.scoreTableVersionId(), v2.scoreTableVersionId());
    }

    @Test
    void readingAcAndGtAreDistinct() {
        ResolvedScoreTable ac = resolve(LEARNER_P, null, "IELTS", "READING_AC", null);
        ResolvedScoreTable gt = resolve(LEARNER_P, null, "IELTS", "READING_GT", null);
        assertNotEquals(ac.scoreTableVersionId(), gt.scoreTableVersionId());
        assertEquals(6.0, ac.converter().orElseThrow().convert(2));
        assertEquals(5.0, gt.converter().orElseThrow().convert(2));
        assertEquals("READING_AC", ac.skill());
        assertEquals("READING_GT", gt.skill());
    }

    @Test
    void resolveIsDeterministic() {
        UUID first = resolve(LEARNER_P, null, "IELTS", "LISTENING", null).scoreTableVersionId();
        for (int i = 0; i < 5; i++) {
            assertEquals(first, resolve(LEARNER_P, null, "IELTS", "LISTENING", null).scoreTableVersionId());
        }
    }

    @Test
    void aTableOfAnotherOrganisationIsNeverFound() {
        // org A's learner takes org A's test: A's override applies
        ResolvedScoreTable mine = resolve(LEARNER_A, ORG_A, "IELTS", "LISTENING", TEST_A);
        assertEquals(overrideA, mine.scoreTableVersionId());
        assertTrue(mine.testOverride());
        // the same test version asked from org B, or from no organisation: A's table is not there, the exam default answers
        assertEquals(defaultVersion, resolve(LEARNER_B, ORG_B, "IELTS", "LISTENING", TEST_A).scoreTableVersionId());
        assertEquals(defaultVersion, resolve(LEARNER_P, null, "IELTS", "LISTENING", TEST_A).scoreTableVersionId());
        // English9x's override is everybody's
        assertEquals(overrideE9, resolve(LEARNER_B, ORG_B, "IELTS", "LISTENING", TEST_E9).scoreTableVersionId());
        assertEquals(overrideE9, resolve(LEARNER_A, ORG_A, "IELTS", "LISTENING", TEST_E9).scoreTableVersionId());
    }

    @Test
    void withoutAContextNothingIsFoundForAnOrganisationsTable() {
        // no binding at all: an empty database context, which RLS answers with English9x's published rows only
        assertEquals(defaultVersion, tables.resolve("IELTS", "LISTENING", Optional.of(TEST_A)).scoreTableVersionId());
    }

    @Test
    void noPublishedTableIsAnErrorNotAGuess() {
        // TOEIC Listening was never created; TOEIC Reading has an archived version only. Nothing is seeded for either (spec 10.2 gives no values).
        assertThrows(ScoreTableNotFoundException.class, () -> resolve(LEARNER_P, null, "TOEIC", "LISTENING", null));
        assertThrows(ScoreTableNotFoundException.class, () -> resolve(LEARNER_P, null, "TOEIC", "READING", null));
    }

    @Test
    void theAggregateFunctionsAreSeededWithoutData() {
        ResolvedScoreTable overall = resolve(LEARNER_P, null, "IELTS", "OVERALL", null);
        assertEquals(ScoreFunction.IELTS_OVERALL, overall.function());
        assertEquals(Optional.empty(), overall.converter());
        assertEquals(ScoreFunction.IELTS_WRITING_WEIGHTED, resolve(LEARNER_P, null, "IELTS", "WRITING", null).function());
        assertEquals(ScoreFunction.SUM, resolve(LEARNER_P, null, "TOEIC", "TOTAL", null).function());
        assertEquals(ScoreFunction.TABLE, resolve(LEARNER_P, null, "IELTS", "LISTENING", null).function());
    }

    @Test
    void anExamKeepsScoresOnlyUnderItsOwnSkills() {
        assertThrows(IllegalArgumentException.class, () -> resolve(LEARNER_P, null, "IELTS", "READING", null));    // IELTS has READING_AC / READING_GT
        assertThrows(IllegalArgumentException.class, () -> resolve(LEARNER_P, null, "TOEIC", "WRITING", null));
        assertThrows(IllegalArgumentException.class, () -> resolve(LEARNER_P, null, "NOPE", "LISTENING", null));
    }

    @Test
    void aPublishedTableThatTableConverterRefusesStopsTheScoringLoudly() {
        // IELTS SPEAKING: no other test reads it, so the result does not depend on the order the tests run in (review M1)
        UUID broken = table(null, "IELTS", "SPEAKING", "EXAM", null);
        UUID bad = version(broken, null, "IELTS", 1, "PUBLISHED", "[{\"raw\":0,\"scaled\":5},{\"raw\":2,\"scaled\":10}]");   // raw 1 is missing
        IllegalStateException e = assertThrows(IllegalStateException.class, () -> resolve(LEARNER_P, null, "IELTS", "SPEAKING", null));
        assertTrue(e.getMessage().contains(bad.toString()), e.getMessage());
    }
}
