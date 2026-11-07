package com.english9x.audit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.english9x.audit.api.AuditLog;
import com.english9x.org.OrgIT;
import com.english9x.org.api.CurrentContextHolder;
import com.english9x.support.TestPostgres;
import java.sql.SQLException;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.dao.DataAccessException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.IllegalTransactionStateException;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

/** The contract of {@link AuditLog}: append-only for everybody, actor and context from the session, same transaction as the action. */
class AuditLogIT extends OrgIT {

    @Autowired AuditLog audit;
    @Autowired PlatformTransactionManager tm;

    private final JdbcTemplate owner = new JdbcTemplate(TestPostgres.owner(DB));
    private final JdbcTemplate appLogin = new JdbcTemplate(TestPostgres.app(DB));

    private void asUser(UUID user, UUID org, Runnable body) {
        try (var ignored = CurrentContextHolder.bind(user, org)) {
            new TransactionTemplate(tm).executeWithoutResult(s -> body.run());
        }
    }

    private static String sqlState(DataAccessException e) {
        for (Throwable t = e; t != null; t = t.getCause()) {
            if (t instanceof SQLException sql) {
                return sql.getSQLState();
            }
        }
        return null;
    }

    private long count(String where, Object... args) {
        return su.queryForObject("select count(*) from audit_log where " + where, Long.class, args);
    }

    @Test
    void appendOnly() {
        UUID u = user("append");
        String target = "append-" + UUID.randomUUID();
        asUser(u, null, () -> audit.record("org.create", "organization", target, Map.of("name", "n")));
        assertEquals(1, count("target_id = ?", target));

        // the owner and a superuser are refused by the trigger (23001), the request role by its missing privileges (42501)
        for (JdbcTemplate who : List.of(owner, su)) {
            DataAccessException upd = assertThrows(DataAccessException.class, () -> who.update("update audit_log set action = 'org.delete' where target_id = ?", target));
            assertEquals("23001", sqlState(upd), upd.getMessage());
            DataAccessException del = assertThrows(DataAccessException.class, () -> who.update("delete from audit_log where target_id = ?", target));
            assertEquals("23001", sqlState(del), del.getMessage());
            DataAccessException tru = assertThrows(DataAccessException.class, () -> who.execute("truncate audit_log"));
            assertEquals("23001", sqlState(tru), tru.getMessage());
        }
        for (String sql : List.of("update audit_log set action = 'org.delete'", "delete from audit_log", "truncate audit_log")) {
            DataAccessException e = assertThrows(DataAccessException.class, () -> appLogin.execute(sql), sql);
            assertEquals("42501", sqlState(e), sql + " -> " + e.getMessage());
        }
        assertEquals(1, count("target_id = ?", target), "the record is still there and unchanged");
        assertEquals("org.create", su.queryForObject("select action from audit_log where target_id = ?", String.class, target));
    }

    @Test
    void recordsActorAndContext() {
        UUID org = org("CENTER");
        UUID u = user("actor");
        member(org, u);
        String inOrg = "ctx-" + UUID.randomUUID();
        String personal = "ctx-" + UUID.randomUUID();
        asUser(u, org, () -> audit.record("role.change", "user", inOrg, Map.of("k", "v")));
        asUser(u, null, () -> audit.record("role.change", "user", personal, Map.of()));

        Map<String, Object> a = su.queryForMap("select actor_kind, actor_id, org_id, created_at, detail::text as detail from audit_log where target_id = ?", inOrg);
        assertEquals("USER", a.get("actor_kind"));
        assertEquals(u, a.get("actor_id"), "the actor is the bound user");
        assertEquals(org, a.get("org_id"), "the owner organisation is the bound context");
        assertTrue(a.get("created_at") != null);
        assertEquals("{\"k\": \"v\"}", a.get("detail"));
        Map<String, Object> b = su.queryForMap("select actor_id, org_id from audit_log where target_id = ?", personal);
        assertEquals(u, b.get("actor_id"));
        assertNull(b.get("org_id"), "no organisation in the personal / English9x context");
    }

    @Test
    void aStatementCannotNameAnotherActorOrContext() {
        UUID u = user("forger");
        UUID other = user("victim");
        UUID orgOfOther = org("CENTER");
        // the application's own connection, bound to u: even with the table in reach it cannot write someone else's name
        try (var ignored = CurrentContextHolder.bind(u, null)) {
            var t = new TransactionTemplate(tm);
            assertThrows(DataAccessException.class, () -> t.executeWithoutResult(s ->
                    appJdbc().update("insert into audit_log(actor_id, action, target_type, target_id) values (?, 'org.create', 'organization', 'forged')", other)));
            assertThrows(DataAccessException.class, () -> t.executeWithoutResult(s ->
                    appJdbc().update("insert into audit_log(org_id, action, target_type, target_id) values (?, 'org.create', 'organization', 'forged')", orgOfOther)));
        }
        assertEquals(0, count("target_id = 'forged'"));
    }

    @Autowired JdbcTemplate jdbc;

    private JdbcTemplate appJdbc() {
        return jdbc;
    }

    @Test
    void recordingJoinsTheCallersTransaction() {
        UUID u = user("tx");
        String gone = "tx-" + UUID.randomUUID();
        String kept = "tx-" + UUID.randomUUID();
        try (var ignored = CurrentContextHolder.bind(u, null)) {
            var t = new TransactionTemplate(tm);
            assertThrows(IllegalStateException.class, () -> t.executeWithoutResult(s -> {
                audit.record("org.create", "organization", gone, Map.of());
                throw new IllegalStateException("the action fails after the record was written");
            }));
            t.executeWithoutResult(s -> audit.record("org.create", "organization", kept, Map.of()));
        }
        assertEquals(0, count("target_id = ?", gone), "if the action rolls back, the record goes too");
        assertEquals(1, count("target_id = ?", kept));
        try (var ignored = CurrentContextHolder.bind(u, null)) {
            assertThrows(IllegalTransactionStateException.class, () -> audit.record("org.create", "organization", "no-tx", Map.of()),
                    "a record outside a transaction would not be atomic with its action");
        }
    }

    @Test
    void noPersonNoRecord() {
        // a background step with no bound user cannot record: it must run bound to the person whose action it records
        assertTrue(CurrentContextHolder.current().isEmpty());
        assertThrows(IllegalStateException.class, () -> new TransactionTemplate(tm).executeWithoutResult(s ->
                audit.record("org.create", "organization", "nobody", Map.of())));
        assertEquals(0, count("target_id = 'nobody'"));
    }

    @Test
    void refusesWhatMustNeverBeLogged() {
        UUID u = user("secrets");
        for (String key : List.of("token", "inviteToken", "password", "newPassword", "passwordHash", "token_hash", "secret", "botSecret",
                "authorization", "cookie", "apiKey", "comment", "text", "body", "answer")) {
            assertThrows(IllegalArgumentException.class, () -> asUser(u, null, () -> audit.record("org.create", "organization", "s", Map.of(key, "x"))), key);
        }
        assertThrows(IllegalArgumentException.class, () -> asUser(u, null, () -> audit.record("org.create", "organization", "s", Map.of("a", Map.of("token", "x")))),
                "nested keys are checked too");
        assertThrows(IllegalArgumentException.class, () -> asUser(u, null, () -> audit.record("org.create", "organization", "s", Map.of("a", "x".repeat(501)))),
                "a long free text is not a detail");
        assertThrows(IllegalArgumentException.class, () -> asUser(u, null, () -> audit.record("org.create", "organization", "s", Map.of("a", new Object()))));
        assertThrows(IllegalArgumentException.class, () -> asUser(u, null, () -> audit.record("Org Create", "organization", "s", Map.of())));
        assertThrows(IllegalArgumentException.class, () -> asUser(u, null, () -> audit.record("org.create", "organization", "", Map.of())));
        assertThrows(IllegalArgumentException.class, () -> asUser(u, null, () -> audit.record("org.create", "organization", "x".repeat(201), Map.of())));
        java.util.Map<String, Object> big = new java.util.LinkedHashMap<>();
        for (int i = 0; i < 100; i++) {
            big.put("k" + i, "v".repeat(100));
        }
        assertThrows(IllegalArgumentException.class, () -> asUser(u, null, () -> audit.record("org.create", "organization", "s", big)),
                "detail is bounded (e9x.audit.max-detail-bytes)");
        assertEquals(0, count("target_id = 's'"));
        // what is allowed: scalars, lists of scalars, nested objects, nulls
        asUser(u, null, () -> audit.record("role.change", "user", "ok-shapes", Map.of("n", 3, "b", true, "list", List.of("A", "B"), "nested", Map.of("x", 1))));
        assertEquals(1, count("target_id = 'ok-shapes'"));
    }
}
