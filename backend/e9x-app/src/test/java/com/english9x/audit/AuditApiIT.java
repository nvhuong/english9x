package com.english9x.audit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.english9x.org.OrgIT;
import com.fasterxml.jackson.databind.JsonNode;
import java.sql.Timestamp;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;

/** GET /api/admin/audit-log: SYS_ADMIN only, filterable, paged in a stable order, size bounded. */
class AuditApiIT extends OrgIT {

    private static String marker() {
        return "zz.t" + UUID.randomUUID().toString().replace("-", "").substring(0, 10);
    }

    private UUID row(UUID actor, UUID org, String action, String targetType, String targetId, String detailJson, Instant at) {
        UUID id = UUID.randomUUID();
        su.update("insert into audit_log(id, created_at, actor_kind, actor_id, org_id, action, target_type, target_id, detail) values (?,?,'USER',?,?,?,?,?,?::jsonb)",
                id, Timestamp.from(at), actor, org, action, targetType, targetId, detailJson);
        return id;
    }

    private List<String> ids(Res r) {
        List<String> out = new ArrayList<>();
        r.body().path("items").forEach(n -> out.add(n.path("id").asText()));
        return out;
    }

    @Test
    void onlyARealSysAdminReads() {
        UUID a = org("CENTER");
        UUID orgAdmin = orgAdmin(a);
        UUID author = user("author");
        grant(author, null, "IELTS", "AUTHOR");
        UUID learner = user("learner");
        UUID sysInOrg = sysAdmin();
        member(a, sysInOrg);
        String action = marker();
        row(learner, a, action, "user", "t1", "{}", Instant.now());
        for (Res r : new Res[] {
            get("/api/admin/audit-log", orgAdmin, a),          // an ORG_ADMIN has no log screen, not even for their own organisation
            get("/api/admin/audit-log", orgAdmin, null),
            get("/api/admin/audit-log", author, null),         // the staff flag alone
            get("/api/admin/audit-log", learner, null),
            get("/api/admin/audit-log", sysInOrg, a),          // SYS_ADMIN inside an organisation context holds no English9x power
            get("/api/admin/audit-log/actions", orgAdmin, a)}) {
            assertEquals(403, r.status(), r.raw());
            assertEquals("urn:e9x:problem:forbidden", r.problemType());
            assertFalse(r.raw().contains(action));
        }
        assertEquals(401, get("/api/admin/audit-log", null, null).status());
        Res ok = get("/api/admin/audit-log?action=" + action, sysAdmin(), null);
        assertEquals(200, ok.status(), ok.raw());
        assertEquals(1, ok.body().path("items").size());
    }

    @Test
    void rowShapeNamesTheActorAndTheOrganisation() {
        UUID a = org("CENTER");
        UUID actor = user("shape");
        String action = marker();
        Instant at = Instant.now().truncatedTo(ChronoUnit.SECONDS);
        row(actor, a, action, "invitation", "inv-1", "{\"orgId\":\"x\",\"roles\":[\"AUTHOR/IELTS\"]}", at);
        JsonNode item = get("/api/admin/audit-log?action=" + action, sysAdmin(), null).body().path("items").get(0);
        assertEquals(actor.toString(), item.path("actorId").asText());
        assertEquals("USER", item.path("actorKind").asText());
        assertEquals(emailOf(actor), item.path("actorEmail").asText());
        assertEquals(a.toString(), item.path("orgId").asText());
        assertEquals(su.queryForObject("select name from organizations where id = ?", String.class, a), item.path("orgName").asText());
        assertEquals(action, item.path("action").asText());
        assertEquals("invitation", item.path("targetType").asText());
        assertEquals("inv-1", item.path("targetId").asText());
        assertEquals(at, Instant.parse(item.path("at").asText()));
        assertEquals("AUTHOR/IELTS", item.path("detail").path("roles").get(0).asText());
    }

    @Test
    void filtersByActionTargetActorAndDate() {
        UUID s = sysAdmin();
        String action = marker();
        String other = marker();
        UUID p = user("p");
        UUID q = user("q");
        Instant t0 = Instant.now().minus(10, ChronoUnit.DAYS);
        UUID r1 = row(p, null, action, "organization", "o1", "{}", t0);
        String tid = marker();
        UUID r2 = row(q, null, action, "user", tid, "{}", t0.plus(2, ChronoUnit.DAYS));
        UUID r3 = row(p, null, other, "user", tid, "{}", t0.plus(4, ChronoUnit.DAYS));
        assertEquals(List.of(r2.toString(), r1.toString()), ids(get("/api/admin/audit-log?action=" + action, s, null)), "newest first");
        assertEquals(List.of(r1.toString()), ids(get("/api/admin/audit-log?action=" + action + "&targetType=organization", s, null)));
        assertEquals(List.of(r3.toString(), r2.toString()), ids(get("/api/admin/audit-log?targetType=user&targetId=" + tid + "&actor=", s, null)), "target type and id; an empty filter is no filter");
        assertEquals(List.of(r1.toString()), ids(get("/api/admin/audit-log?action=" + action + "&actor=" + p, s, null)), "actor by id");
        assertEquals(List.of(r2.toString()), ids(get("/api/admin/audit-log?action=" + action + "&actor=" + emailOf(q).toUpperCase(), s, null)), "actor by address, any case");
        assertEquals(List.of(r2.toString()), ids(get("/api/admin/audit-log?action=" + action + "&from=" + t0.plus(1, ChronoUnit.DAYS), s, null)), "from");
        assertEquals(List.of(r1.toString()), ids(get("/api/admin/audit-log?action=" + action + "&to=" + t0.plus(1, ChronoUnit.DAYS), s, null)), "to");
        assertEquals(List.of(r2.toString()), ids(get("/api/admin/audit-log?action=" + action + "&from=" + t0.plus(1, ChronoUnit.DAYS) + "&to=" + t0.plus(3, ChronoUnit.DAYS), s, null)));
        assertEquals(List.of(), ids(get("/api/admin/audit-log?action=" + action + "&actor=" + UUID.randomUUID(), s, null)));
        // the text of a filter is data, not SQL
        assertEquals(200, get("/api/admin/audit-log?targetId=" + "'%20or%201=1;--", s, null).status());
        assertEquals(List.of(), ids(get("/api/admin/audit-log?targetId=" + "'%20or%201=1;--", s, null)));
        assertEquals(400, get("/api/admin/audit-log?from=yesterday", s, null).status());
        assertEquals(400, get("/api/admin/audit-log?to=2026-13-45", s, null).status());
    }

    @Test
    void pagesInAStableOrderAndSizeIsBounded() {
        UUID s = sysAdmin();
        String action = marker();
        Instant same = Instant.now().minus(1, ChronoUnit.HOURS);
        List<String> expected = new ArrayList<>();
        for (int i = 0; i < 7; i++) {
            expected.add(0, row(user("pg"), null, action, "user", "t" + i, "{}", same).toString()); // identical timestamps: the order must not depend on them
        }
        List<String> walked = new ArrayList<>();
        for (int page = 0; page < 3; page++) {
            Res r = get("/api/admin/audit-log?action=" + action + "&size=3&page=" + page, s, null);
            assertEquals(200, r.status(), r.raw());
            assertEquals(3, r.body().path("size").asInt());
            assertEquals(page, r.body().path("page").asInt());
            assertEquals(page < 2, r.body().path("hasMore").asBoolean(), "page " + page);
            walked.addAll(ids(r));
        }
        assertEquals(expected, walked, "newest first, ties broken by insertion order, every row once");
        assertEquals(ids(get("/api/admin/audit-log?action=" + action + "&size=3&page=1", s, null)), ids(get("/api/admin/audit-log?action=" + action + "&size=3&page=1", s, null)));

        Res huge = get("/api/admin/audit-log?size=100000", s, null);
        assertEquals(200, huge.status());
        assertEquals(100, huge.body().path("size").asInt(), "e9x.audit.max-page-size");
        assertTrue(huge.body().path("items").size() <= 100);
        assertEquals(50, get("/api/admin/audit-log", s, null).body().path("size").asInt(), "e9x.audit.default-page-size");
        assertEquals(400, get("/api/admin/audit-log?size=0", s, null).status());
        assertEquals(400, get("/api/admin/audit-log?page=-1", s, null).status());
        assertEquals(400, get("/api/admin/audit-log?size=abc", s, null).status());
    }

    @Test
    void detailComesBackAsDataAndTheKnownActionsAreListed() {
        UUID s = sysAdmin();
        String action = marker();
        row(user("x"), null, action, "user", "t", "{\"name\":\"<img src=x onerror=alert(1)>\"}", Instant.now());
        Res r = get("/api/admin/audit-log?action=" + action, s, null);
        assertEquals("<img src=x onerror=alert(1)>", r.body().path("items").get(0).path("detail").path("name").asText(), "verbatim, as text");
        assertTrue(r.headers().getFirst("Content-Type").startsWith("application/json"));
        Res actions = get("/api/admin/audit-log/actions", s, null);
        assertEquals(200, actions.status(), actions.raw());
        List<String> names = new ArrayList<>();
        actions.body().forEach(n -> names.add(n.asText()));
        assertTrue(names.contains(action), names.toString());
        assertEquals(names.stream().sorted().toList(), names, "sorted");
    }
}
