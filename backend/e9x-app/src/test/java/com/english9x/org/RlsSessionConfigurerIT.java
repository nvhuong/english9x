package com.english9x.org;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.english9x.org.api.CurrentContext;
import com.english9x.org.api.CurrentContextHolder;
import com.english9x.org.api.OrgContextDeniedException;
import com.english9x.support.TestPostgres;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.function.Supplier;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionTemplate;

/** Every transaction runs under the bound user / org, with privilege flags derived from role data. */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.NONE)
class RlsSessionConfigurerIT {

    static final String DB = TestPostgres.createDatabase();

    static final UUID ORG_A = UUID.randomUUID();
    static final UUID ORG_B = UUID.randomUUID();
    static final UUID LEARNER_A = UUID.randomUUID();
    static final UUID ADMIN_A = UUID.randomUUID();
    static final UUID ADMIN_A_IN_B_AS_MEMBER = UUID.randomUUID();
    static final UUID LEARNER_B = UUID.randomUUID();
    static final UUID AUTHOR = UUID.randomUUID();
    static final UUID SYS_ADMIN = UUID.randomUUID();
    static final UUID OUTSIDER = UUID.randomUUID();
    static final UUID CLASS_A = UUID.randomUUID();
    static final UUID CLASS_B = UUID.randomUUID();

    @DynamicPropertySource
    static void database(DynamicPropertyRegistry registry) {
        TestPostgres.connect(registry, DB);
    }

    @Autowired
    JdbcTemplate jdbc;

    @Autowired
    PlatformTransactionManager tm;

    @Autowired
    ObjectProvider<CurrentContext> currentContextProvider;

    @BeforeAll
    static void fixtures(@Autowired JdbcTemplate ignoredToForceMigration) {
        JdbcTemplate su = new JdbcTemplate(TestPostgres.superuser(DB));
        su.update("insert into organizations(id,name,type) values (?,'A','CENTER'),(?,'B','CENTER')", ORG_A, ORG_B);
        for (UUID u : List.of(LEARNER_A, ADMIN_A, ADMIN_A_IN_B_AS_MEMBER, LEARNER_B, AUTHOR, SYS_ADMIN, OUTSIDER)) {
            su.update("insert into users(id,email) values (?,?)", u, u + "@x.test");
        }
        for (Object[] m : new Object[][] {
            {ORG_A, LEARNER_A}, {ORG_A, ADMIN_A}, {ORG_B, ADMIN_A_IN_B_AS_MEMBER}, {ORG_A, ADMIN_A_IN_B_AS_MEMBER},
            {ORG_B, LEARNER_B}
        }) {
            su.update("insert into org_memberships(org_id,user_id) values (?,?)", m);
        }
        su.update("insert into role_grants(user_id,org_id,exam_code,role) values (?,?,null,'ORG_ADMIN')", ADMIN_A, ORG_A);
        // an ORG_ADMIN of A who is only a plain member of B
        su.update("insert into role_grants(user_id,org_id,exam_code,role) values (?,?,null,'ORG_ADMIN')", ADMIN_A_IN_B_AS_MEMBER, ORG_A);
        su.update("insert into role_grants(user_id,org_id,exam_code,role) values (?,null,'IELTS','AUTHOR')", AUTHOR);
        su.update("insert into role_grants(user_id,org_id,exam_code,role) values (?,null,null,'SYS_ADMIN')", SYS_ADMIN);
        su.update("insert into classes(id,owner_org_id,name) values (?,?,'A'),(?,?,'B')", CLASS_A, ORG_A, CLASS_B, ORG_B);
    }

    private <T> T inTx(UUID user, UUID org, Supplier<T> work) {
        return inTx(user, org, new TransactionTemplate(tm), work);
    }

    private <T> T inTx(UUID user, UUID org, TransactionTemplate tx, Supplier<T> work) {
        try (var scope = user == null ? null : CurrentContextHolder.bind(user, org)) {
            return tx.execute(status -> work.get());
        }
    }

    private Map<String, String> settings() {
        return jdbc.queryForMap("""
                select coalesce(current_setting('app.user_id', true), '') as user_id,
                       coalesce(current_setting('app.org_id', true), '') as org_id,
                       coalesce(current_setting('app.e9x_staff', true), '') as staff,
                       coalesce(current_setting('app.org_admin', true), '') as org_admin
                """)
                .entrySet().stream()
                .collect(java.util.stream.Collectors.toMap(Map.Entry::getKey, e -> String.valueOf(e.getValue())));
    }

    @Test
    void transactionRunsUnderTheBoundUserAndOrg() {
        Map<String, String> s = inTx(LEARNER_A, ORG_A, this::settings);
        assertEquals(LEARNER_A.toString(), s.get("user_id"));
        assertEquals(ORG_A.toString(), s.get("org_id"));
        assertEquals("", s.get("staff"));
        assertEquals("", s.get("org_admin"));
    }

    @Test
    void rlsFiltersByTheBoundOrganisation() {
        Supplier<List<UUID>> classes = () -> jdbc.queryForList("select id from classes", UUID.class);
        assertEquals(List.of(CLASS_A), inTx(LEARNER_A, ORG_A, classes));
        assertEquals(List.of(CLASS_B), inTx(LEARNER_B, ORG_B, classes));
        assertEquals(List.of(), inTx(LEARNER_A, null, classes), "personal context sees no org rows");
        assertEquals(List.of(), inTx(null, null, classes), "no context at all sees nothing");
    }

    @Test
    void orgAdminFlagFollowsTheOrgAdminGrantOfThatOrganisationOnly() {
        assertEquals("on", inTx(ADMIN_A, ORG_A, this::settings).get("org_admin"));
        assertEquals("", inTx(LEARNER_A, ORG_A, this::settings).get("org_admin"));
        // member of B, ORG_ADMIN of A: in B they are only a member
        assertEquals("", inTx(ADMIN_A_IN_B_AS_MEMBER, ORG_B, this::settings).get("org_admin"));
        assertEquals("on", inTx(ADMIN_A_IN_B_AS_MEMBER, ORG_A, this::settings).get("org_admin"));
        // never in the personal context
        assertEquals("", inTx(ADMIN_A, null, this::settings).get("org_admin"));
    }

    @Test
    void staffFlagNeedsAnEnglish9xRoleAndThePersonalContext() {
        assertEquals("on", inTx(AUTHOR, null, this::settings).get("staff"));
        assertEquals("on", inTx(SYS_ADMIN, null, this::settings).get("staff"));
        assertEquals("", inTx(LEARNER_A, null, this::settings).get("staff"), "a learner is not staff");
        assertEquals("", inTx(ADMIN_A, null, this::settings).get("staff"), "an ORG_ADMIN is not English9x staff");
        // an English9x role does not carry into an organisation context (and the author is no member of one)
        assertThrows(RuntimeException.class, () -> inTx(AUTHOR, ORG_A, this::settings));
    }

    @Test
    void actingInAnOrganisationYouDoNotBelongToIsDenied() {
        RuntimeException e = assertThrows(RuntimeException.class, () -> inTx(OUTSIDER, ORG_A, this::settings));
        assertTrue(hasCause(e, OrgContextDeniedException.class), "cause chain: " + e);
        e = assertThrows(RuntimeException.class, () -> inTx(LEARNER_A, ORG_B, this::settings));
        assertTrue(hasCause(e, OrgContextDeniedException.class), "cause chain: " + e);
        // the failed begin must not leave the connection or the thread in a bad state
        assertEquals(List.of(CLASS_A), inTx(LEARNER_A, ORG_A, () -> jdbc.queryForList("select id from classes", UUID.class)));
    }

    @Test
    void requiresNewAndReadOnlyTransactionsAreConfiguredToo() {
        TransactionTemplate readOnly = new TransactionTemplate(tm);
        readOnly.setReadOnly(true);
        assertEquals(LEARNER_A.toString(), inTx(LEARNER_A, ORG_A, readOnly, this::settings).get("user_id"));

        TransactionTemplate requiresNew = new TransactionTemplate(tm);
        requiresNew.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
        Map<String, String> inner = inTx(LEARNER_A, ORG_A, () -> requiresNew.execute(st -> settings()));
        assertEquals(ORG_A.toString(), inner.get("org_id"));
    }

    @Test
    void noTransactionNoContext() {
        try (var scope = CurrentContextHolder.bind(ADMIN_A, ORG_A)) {
            // a statement outside any transaction is not given a context: RLS answers with no rows
            assertEquals(List.of(), jdbc.queryForList("select id from classes", UUID.class));
            assertEquals("", settings().get("org_admin"));
        }
    }

    @Test
    void currentContextBeanExposesTheBindingAndTheDerivedFlags() {
        CurrentContext currentContext = currentContextProvider.getObject();
        assertEquals(
                List.of(true, true, ORG_A.toString(), ADMIN_A.toString()),
                inTx(ADMIN_A, ORG_A, () -> List.of(
                        currentContext.isOrgAdmin(), !currentContext.isEnglish9xStaff(),
                        currentContext.orgId().orElseThrow().toString(), currentContext.userId().toString())));
        assertTrue(inTx(AUTHOR, null, () -> currentContext.isEnglish9xStaff()));
        assertTrue(inTx(AUTHOR, null, () -> currentContext.orgId().isEmpty()));
        // outside a transaction the flags are false (fail closed)
        try (var scope = CurrentContextHolder.bind(AUTHOR, null)) {
            assertFalse(currentContext.isEnglish9xStaff());
            assertFalse(currentContext.isOrgAdmin());
        }
    }

    private static boolean hasCause(Throwable t, Class<? extends Throwable> type) {
        for (Throwable c = t; c != null; c = c.getCause()) {
            if (type.isInstance(c)) {
                return true;
            }
        }
        return false;
    }
}
