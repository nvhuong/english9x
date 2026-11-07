package com.english9x.org;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.english9x.org.probe.ContextProbeGates;
import com.fasterxml.jackson.databind.JsonNode;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpMethod;
import org.springframework.test.context.ActiveProfiles;

/**
 * Review Focus #1 on the server: a person who belongs to two organisations switches context while a request is in flight.
 * Real concurrent HTTP requests against PostgreSQL with Row-Level Security, synchronised by latches and a barrier inside the
 * request (never by sleeping): every request must see exactly the data of the context it was sent with, from its first read
 * to its last.
 */
@ActiveProfiles("ctx-probe")
class ContextSwitchIsolationIT extends OrgIT {

    static UUID orgA;
    static UUID orgB;
    static UUID person;
    static String classA;
    static String classB;
    static String token;

    @BeforeAll
    static void fixtures(@org.springframework.beans.factory.annotation.Autowired org.springframework.jdbc.core.JdbcTemplate migrated) {
        org.springframework.jdbc.core.JdbcTemplate su = new org.springframework.jdbc.core.JdbcTemplate(com.english9x.support.TestPostgres.superuser(DB));
        orgA = UUID.randomUUID();
        orgB = UUID.randomUUID();
        person = UUID.randomUUID();
        classA = "class-of-A-" + orgA;
        classB = "class-of-B-" + orgB;
        su.update("insert into organizations(id,name,type) values (?,'A','CENTER'),(?,'B','CENTER')", orgA, orgB);
        su.update("insert into users(id,email,email_verified_at,consent_at) values (?,?,now(),now())", person, "iso-" + person + "@example.test");
        su.update("insert into org_memberships(org_id,user_id) values (?,?),(?,?)", orgA, person, orgB, person);
        su.update("insert into classes(owner_org_id,name) values (?,?),(?,?)", orgA, classA, orgB, classB);
        token = com.english9x.support.TestAuth.accessToken(person);
    }

    private Res probe(UUID ctx, String gate, String mode) {
        Map<String, String> h = new java.util.HashMap<>();
        if (ctx != null) {
            h.put("X-Org-Context", ctx.toString());
        }
        if (gate != null) {
            h.put("X-Probe-Gate", gate);
            h.put("X-Probe-Mode", mode);
        }
        return call(HttpMethod.GET, "/api/probe/classes", null, token, h);
    }

    private static List<String> list(JsonNode n) {
        List<String> out = new ArrayList<>();
        n.forEach(x -> out.add(x.asText()));
        return out;
    }

    @Test
    void inFlightRequestUsesItsOwnContext() throws Exception {
        String gate = UUID.randomUUID().toString();
        ContextProbeGates.open(gate, 1);
        // request 1 enters organisation A and parks inside its transaction
        CompletableFuture<Res> inFlight = CompletableFuture.supplyAsync(() -> probe(orgA, gate, "hold"));
        assertTrue(ContextProbeGates.get(gate).arrived.await(30, TimeUnit.SECONDS), "request 1 is inside its transaction");

        // the person switches: requests for B (and for the personal context) run to completion while request 1 is in flight
        Res b = probe(orgB, null, "none");
        Res personal = probe(null, null, "none");
        assertEquals(List.of(classB), list(b.body().path("after")), b.raw());
        assertEquals(List.of(), list(personal.body().path("after")), "the personal context has no organisation data: " + personal.raw());

        // request 1 continues: still organisation A, from its first read to its last
        ContextProbeGates.get(gate).proceed.countDown();
        Res a = inFlight.get(30, TimeUnit.SECONDS);
        assertEquals(200, a.status(), a.raw());
        assertEquals(List.of(classA), list(a.body().path("before")));
        assertEquals(List.of(classA), list(a.body().path("after")), "no row of B appeared in A's request: " + a.raw());
        assertEquals(orgA.toString(), a.body().path("settingBefore").asText());
        assertEquals(orgA.toString(), a.body().path("settingAfter").asText(), "the database session setting stayed A's");
        assertEquals(orgA.toString(), a.body().path("boundOrg").asText());
    }

    @Test
    void manyRequestsInDifferentContextsAtTheSameInstantStayApart() throws Exception {
        Random random = new Random(42);
        for (int round = 0; round < 15; round++) {
            int n = 6; // fewer than the pool, so all of them are inside a transaction at once
            String gate = UUID.randomUUID().toString();
            ContextProbeGates.open(gate, n);
            List<UUID> ctxs = new ArrayList<>();
            for (int i = 0; i < n; i++) {
                int pick = random.nextInt(3);
                ctxs.add(pick == 0 ? null : (pick == 1 ? orgA : orgB));
            }
            List<CompletableFuture<Res>> futures = new ArrayList<>();
            for (UUID ctx : ctxs) {
                futures.add(CompletableFuture.supplyAsync(() -> probe(ctx, gate, "barrier")));
            }
            for (int i = 0; i < n; i++) {
                Res r = futures.get(i).get(60, TimeUnit.SECONDS);
                assertEquals(200, r.status(), r.raw());
                UUID ctx = ctxs.get(i);
                List<String> expected = ctx == null ? List.of() : List.of(ctx.equals(orgA) ? classA : classB);
                assertEquals(expected, list(r.body().path("before")), "round " + round + " request " + i + ": " + r.raw());
                assertEquals(expected, list(r.body().path("after")), "round " + round + " request " + i + ": " + r.raw());
                assertEquals(ctx == null ? "" : ctx.toString(), r.body().path("settingAfter").asText());
                assertEquals(ctx == null ? "" : ctx.toString(), r.body().path("boundOrg").asText());
            }
            ContextProbeGates.close(gate);
        }
    }

    @Test
    void aThreadThatServedOneContextCarriesNothingToTheNextRequest() {
        // many sequential requests alternate contexts; the container reuses its threads
        for (int i = 0; i < 60; i++) {
            UUID ctx = i % 3 == 0 ? orgA : (i % 3 == 1 ? null : orgB);
            Res r = probe(ctx, null, "none");
            List<String> expected = ctx == null ? List.of() : List.of(ctx.equals(orgA) ? classA : classB);
            assertEquals(expected, list(r.body().path("after")), "request " + i + ": " + r.raw());
            assertEquals(ctx == null ? "" : ctx.toString(), r.body().path("boundOrg").asText());
        }
    }
}
