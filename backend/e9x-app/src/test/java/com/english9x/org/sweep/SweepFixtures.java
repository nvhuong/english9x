package com.english9x.org.sweep;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import org.springframework.jdbc.core.JdbcTemplate;

/** Two tenants with data in several RLS tables, seeded as superuser. A is the caller; B owns the foreign ids. */
public final class SweepFixtures {

    public final UUID orgA = UUID.randomUUID();
    public final UUID orgB = UUID.randomUUID();
    public final UUID userA = UUID.randomUUID();
    public final UUID userB = UUID.randomUUID();
    public final UUID classA = UUID.randomUUID();
    public final UUID classB = UUID.randomUUID();

    private final JdbcTemplate su;

    public SweepFixtures(JdbcTemplate superuser) {
        this.su = superuser;
    }

    public void seed() {
        su.update("insert into organizations(id,name,type) values (?,'A','CENTER'),(?,'B','CENTER')", orgA, orgB);
        su.update("insert into users(id,email) values (?,'a@x.test'),(?,'b@x.test')", userA, userB);
        su.update("insert into org_memberships(org_id,user_id) values (?,?),(?,?)", orgA, userA, orgB, userB);
        // identities are readable by their owner only (policy auth_identities_select); B's row must stay invisible to A
        su.update("insert into auth_identities(user_id,provider,subject,email) values (?,'google','sweep-sub-b','b@x.test')", userB);
        su.update("insert into classes(id,owner_org_id,name) values (?,?,'class A'),(?,?,'class B')", classA, orgA, classB, orgB);
        su.update("insert into exams(code,name) values ('IELTS','IELTS') on conflict do nothing");
        su.update("insert into courses(owner_org_id,exam_code,code,title,status) values (?,'IELTS','B00001','B draft','DRAFT')", orgB);
        su.update("insert into questions(owner_org_id,exam_code,content,answer_key,status) values (?,'IELTS','{}','{}','DRAFT')", orgB);
        su.update("insert into exam_attempts(user_id,owner_org_id,test_version_id,status) values (?,?,gen_random_uuid(),'IN_PROGRESS')", userB, orgB);
        su.update("insert into role_grants(user_id,org_id,exam_code,role) values (?,?,null,'ORG_ADMIN')", userB, orgB);
        // A's member is A's ORG_ADMIN, so the org-admin endpoints (/api/orgs/{id}/...) have a positive control
        su.update("insert into role_grants(user_id,org_id,exam_code,role) values (?,?,null,'ORG_ADMIN')", userA, orgA);
        su.update("insert into invitations(org_id,email,grants,token_hash,invited_by,expires_at) values (?,?,'[{\"role\":\"LEARNER\",\"exam\":null}]',sha256('sweep-b'::bytea),?,now()+interval '1 day')",
                orgB, "invited-b@x.test", userB);
        su.update("insert into invitations(org_id,email,grants,token_hash,invited_by,expires_at) values (?,?,'[{\"role\":\"LEARNER\",\"exam\":null}]',sha256('sweep-a'::bytea),?,now()+interval '1 day')",
                orgA, "invited-a@x.test", userA);
        su.update("insert into join_codes(org_id,code_hash,created_by,expires_at) values (?,sha256('sweep-code-b'::bytea),?,now()+interval '1 day')", orgB, userB);
        // P1.6: org B's review bundle and review (an exam-scoped REVIEWER of org A must find neither)
        UUID bundleB = UUID.randomUUID();
        su.update("insert into review_bundles(id,owner_org_id,submitted_by,member_count) values (?,?,?,1)", bundleB, orgB, userB);
        su.update("insert into review_requests(bundle_id,content_type,version_id,owner_org_id,exam_code,author_id,submitted_by) "
                + "values (?,'item',gen_random_uuid(),?,'IELTS',?,?)", bundleB, orgB, userB, userB);
        su.update("insert into entitlements(user_id,course_id,valid_from,valid_until,source_seat_id,source_org_id) "
                + "select ?, id, now() - interval '1 day', now() + interval '1 day', gen_random_uuid(), ? from courses where owner_org_id = ?",
                userB, orgB, orgB);
    }

    /** Ids of org B that org A may not see, plus everything the catalog walk could not classify or find. */
    public record Catalog(List<UUID> ids, List<String> problems) {}

    /** Tables whose foreign ids are seeded explicitly above (they carry no tenant column of their own). */
    private static final Set<String> EXPLICIT = Set.of("users", "organizations");

    /**
     * Infrastructure tables that hold no tenant data a request can reach: `jobs` (P1.7) grants the request role
     * INSERT only, no SELECT, and only the worker login reads it, so no endpoint can return a foreign row. The three
     * token tables of P1.4 are in the same position (no table privilege for the request role).
     */
    private static final Set<String> INFRASTRUCTURE = Set.of("jobs",
            // P1.4: the request role has no privilege at all on these (only the SECURITY DEFINER auth_* functions touch them)
            "refresh_tokens", "email_verifications", "password_resets");

    /**
     * Walks every public table that has an {@code id uuid}. A table must be either explicit ({@code users},
     * {@code organizations}), listed in {@code exemptTables} with a reason, or tenant-linked by
     * {@code owner_org_id} / {@code org_id} / {@code source_org_id} / {@code user_id}; a tenant-linked table must
     * hold at least one row of org B. Anything else is a problem, so a table or endpoint added by a later task cannot
     * be silently skipped: its author must seed a foreign row here or write down why it is exempt.
     */
    public Catalog foreignIds(Map<String, String> exemptTables) {
        List<UUID> ids = new ArrayList<>(List.of(orgB, userB));
        List<String> problems = new ArrayList<>();
        List<String> tables = su.queryForList("""
                select c.table_name from information_schema.columns c
                  join information_schema.tables t on t.table_schema = c.table_schema and t.table_name = c.table_name
                 where c.table_schema = 'public' and t.table_type = 'BASE TABLE'
                   and c.column_name = 'id' and c.data_type = 'uuid'
                   and c.table_name <> 'flyway_schema_history'
                 order by 1
                """, String.class);
        for (String t : tables) {
            if (EXPLICIT.contains(t) || INFRASTRUCTURE.contains(t) || exemptTables.containsKey(t)) {
                continue;
            }
            List<String> cols = su.queryForList(
                    "select column_name from information_schema.columns where table_schema='public' and table_name=?", String.class, t);
            List<String> conds = new ArrayList<>();
            List<Object> args = new ArrayList<>();
            for (String col : List.of("owner_org_id", "org_id", "source_org_id")) {
                if (cols.contains(col)) {
                    conds.add(col + " = ?");
                    args.add(orgB);
                }
            }
            if (cols.contains("user_id")) {
                conds.add("user_id in (select user_id from org_memberships where org_id = ?)");
                args.add(orgB);
            }
            if (conds.isEmpty()) {
                problems.add("table " + t + " has an id but no owner_org_id/org_id/source_org_id/user_id: classify it or list it in the exempt tables with a reason");
                continue;
            }
            List<UUID> rows = su.queryForList("select id from \"" + t + "\" where " + String.join(" or ", conds), UUID.class, args.toArray());
            if (rows.isEmpty()) {
                problems.add("table " + t + " has no row belonging to org B: seed one in SweepFixtures so endpoints over it are really swept");
            }
            ids.addAll(rows);
        }
        return new Catalog(ids.stream().distinct().toList(), problems);
    }
}
