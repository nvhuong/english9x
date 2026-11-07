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
    /** Member of org A without roles to speak of: the positive control of changing somebody's roles. */
    public final UUID memberA = UUID.randomUUID();
    /** Holds SYS_ADMIN: the positive control of the routes that org A's admin must be refused. */
    public final UUID admin = UUID.randomUUID();
    public final UUID invitationA = UUID.randomUUID();
    public final UUID invitationB = UUID.randomUUID();
    public final UUID staffInvitation = UUID.randomUUID();
    public final UUID notificationA = UUID.randomUUID();
    public final String courseCodeB = "B00001";
    /** Raw tokens of an invitation of org B, and of one org A's member may really accept (plausible: 40-64 URL-safe characters). */
    public final String tokenB = "sweep-foreign-invitation-token-of-org-b-0001";
    public final String tokenForA = "sweep-own-invitation-token-for-the-a-member1";

    private final JdbcTemplate su;

    public SweepFixtures(JdbcTemplate superuser) {
        this.su = superuser;
    }

    public void seed() {
        su.update("insert into organizations(id,name,type) values (?,'A','CENTER'),(?,'B','CENTER')", orgA, orgB);
        // A's member is verified, so accepting an invitation can only fail for the reason under test
        su.update("insert into users(id,email,email_verified_at,consent_at) values (?,'a@x.test',now(),now()),(?,'b@x.test',now(),now()),(?,'member-a@x.test',now(),now()),(?,'admin@x.test',now(),now())",
                userA, userB, memberA, admin);
        su.update("insert into org_memberships(org_id,user_id) values (?,?),(?,?),(?,?)", orgA, userA, orgB, userB, orgA, memberA);
        su.update("insert into role_grants(user_id,org_id,exam_code,role) values (?,null,null,'SYS_ADMIN')", admin);
        // identities are readable by their owner only (policy auth_identities_select); B's row must stay invisible to A
        su.update("insert into auth_identities(user_id,provider,subject,email) values (?,'google','sweep-sub-b','b@x.test')", userB);
        su.update("insert into classes(id,owner_org_id,name) values (?,?,'class A'),(?,?,'class B')", classA, orgA, classB, orgB);
        su.update("insert into exams(code,name) values ('IELTS','IELTS') on conflict do nothing");
        su.update("insert into courses(owner_org_id,exam_code,code,title,status) values (?,'IELTS',?,'B draft','DRAFT')", orgB, courseCodeB);
        su.update("insert into questions(owner_org_id,exam_code,content,answer_key,status) values (?,'IELTS','{}','{}','DRAFT')", orgB);
        su.update("insert into exam_attempts(user_id,owner_org_id,test_version_id,status) values (?,?,gen_random_uuid(),'IN_PROGRESS')", userB, orgB);
        su.update("insert into role_grants(user_id,org_id,exam_code,role) values (?,?,null,'ORG_ADMIN')", userB, orgB);
        // A's member is A's ORG_ADMIN, so the org-admin endpoints (/api/orgs/{id}/...) have a positive control
        su.update("insert into role_grants(user_id,org_id,exam_code,role) values (?,?,null,'ORG_ADMIN')", userA, orgA);
        String invitation = "insert into invitations(id,org_id,email,grants,token_hash,invited_by,expires_at) values (?,?,?,'[{\"role\":\"LEARNER\",\"exam\":null}]',sha256(convert_to(?,'UTF8')),?,now()+interval '1 day')";
        su.update(invitation, invitationB, orgB, "invited-b@x.test", tokenB, userB);
        su.update(invitation, invitationA, orgA, "invited-a@x.test", "sweep-a", userA);
        su.update(invitation, UUID.randomUUID(), orgA, "a@x.test", tokenForA, userA);
        su.update("insert into invitations(id,org_id,email,grants,token_hash,invited_by,expires_at) values (?,null,'staff@x.test','[{\"role\":\"REVIEWER\",\"exam\":\"IELTS\"}]',sha256('sweep-staff'::bytea),?,now()+interval '1 day')",
                staffInvitation, admin);
        su.update("insert into join_codes(org_id,code_hash,created_by,expires_at) values (?,sha256('sweep-code-b'::bytea),?,now()+interval '1 day')", orgB, userB);
        // P1.6: org B's review bundle and review (org A's ORG_ADMIN, the sweep's caller, must find neither; no review endpoint exists yet, P2.9 adds it and the sweep then covers it)
        UUID bundleB = UUID.randomUUID();
        su.update("insert into review_bundles(id,owner_org_id,submitted_by,member_count) values (?,?,?,1)", bundleB, orgB, userB);
        su.update("insert into review_requests(bundle_id,content_type,version_id,owner_org_id,exam_code,author_id,submitted_by) "
                + "values (?,'item',gen_random_uuid(),?,'IELTS',?,?)", bundleB, orgB, userB, userB);
        // P1.8: org B's member has a notification (and an unused Telegram link token): the notification endpoints are per person, so
        // A must neither list nor mark them, in any context (id-bearing paths of /api/me/notifications are swept like the others)
        su.update("insert into notifications(user_id,event_type,data,dedupe_key) values (?,'REVIEW_REQUESTED','{\"examCode\":\"IELTS\"}','sweep-b')", userB);
        su.update("insert into notifications(id,user_id,event_type,data,dedupe_key) values (?,?,'REVIEW_REQUESTED','{\"examCode\":\"IELTS\"}','sweep-a')", notificationA, userA);
        su.update("insert into telegram_link_tokens(user_id,token_hash,expires_at) values (?,sha256('sweep-tg-b'::bytea),now()+interval '1 day')", userB);
        // keys that are not an `id uuid` column: composite (user, event, channel) and a user id as the key
        su.update("insert into notification_prefs(user_id,event_type,channel,enabled) values (?,'SCORE_OFFICIAL','EMAIL',false)", userB);
        su.update("insert into telegram_links(user_id,chat_id) values (?,424242)", userB);
        // P1.9: org B's audit records (the log has no tenant endpoint at all, only the SYS_ADMIN screen; A's ORG_ADMIN must never see one)
        su.update("insert into audit_log(actor_id,org_id,action,target_type,target_id,detail) values (?,?,'role.change','user',?,'{\"orgId\":\"b\"}')", userB, orgB, userB.toString());
        su.update("insert into entitlements(user_id,course_id,valid_from,valid_until,source_seat_id,source_org_id) "
                + "select ?, id, now() - interval '1 day', now() + interval '1 day', gen_random_uuid(), ? from courses where owner_org_id = ?",
                userB, orgB, orgB);
    }

    // ---- the operations of the API that are not simply read with an id: the one list of exemptions, and the fixtures ------------------------------

    private static final String PUBLIC = "public, before there is a tenant: takes no organisation or user id, a token or an address at most, and answers the same for everybody";
    private static final String OWN = "about the signed-in person only: no id in the request, the database function or policy takes the person from the session";

    /** Operations that take no tenant's id, each with its reason. Anything else that writes needs a fixture in {@link #writeFixtures()}. */
    public static final Map<String, String> EXEMPT_OPERATIONS = Map.ofEntries(
            Map.entry("POST /api/auth/register", PUBLIC),
            Map.entry("POST /api/auth/login", PUBLIC),
            Map.entry("POST /api/auth/logout", PUBLIC),
            Map.entry("POST /api/auth/refresh", PUBLIC),
            Map.entry("POST /api/auth/verify-email", PUBLIC),
            Map.entry("POST /api/auth/verify-email/resend", PUBLIC),
            Map.entry("POST /api/auth/password-reset/request", PUBLIC),
            Map.entry("POST /api/auth/password-reset/confirm", PUBLIC),
            Map.entry("POST /api/auth/oauth/exchange", PUBLIC),
            Map.entry("POST /api/auth/oauth/consent", PUBLIC),
            Map.entry("POST /api/telegram/webhook", "called by Telegram with a shared secret header and no user; any other caller gets 401 (TelegramLinkIT)"),
            Map.entry("POST /api/me/notifications/read-all", OWN),
            Map.entry("PUT /api/me/notification-prefs", OWN),
            Map.entry("POST /api/me/telegram/link", OWN),
            Map.entry("DELETE /api/me/telegram", OWN));

    /** A minimal valid invitation: what makes the answer depend on who asks, not on the body. */
    private static final String INVITE = "{\"email\":\"sweep-new@x.test\",\"roles\":[{\"role\":\"LEARNER\"}]}";

    private static Map<String, String> slot(String name, Object value) {
        return Map.of(name, value.toString());
    }

    private static Map<String, String> slots(String a, Object av, String b, Object bv) {
        return Map.of(a, av.toString(), b, bv.toString());
    }

    /**
     * How every write endpoint of the application is swept. Every entry mixes "mine" and "foreign" where the route has two ids, because authorising the
     * parent and trusting the child is the classic hole.
     */
    public Map<String, ForeignIdSweep.Fixture> writeFixtures() {
        String roles = "{\"userId\":\"" + memberA + "\",\"roles\":[{\"role\":\"LEARNER\"}]}";
        return Map.of(
                "POST /api/me/notifications/{id}/read", ForeignIdSweep.Fixture.write(null, List.of(), ForeignIdSweep.Control.of(slot("id", notificationA), null)),
                "POST /api/orgs/{id}/invitations", ForeignIdSweep.Fixture.write(INVITE, List.of(), ForeignIdSweep.Control.of(slot("id", orgA), INVITE)),
                // the walk with A's member named in the body, then A's own organisation with B's member in the body
                "PUT /api/orgs/{id}/role-grants", new ForeignIdSweep.Fixture(roles, foreignOrgsWithRoles(roles), ForeignIdSweep.Control.of(slot("id", orgA), roles), null),
                "DELETE /api/orgs/{id}/invitations/{invitationId}", ForeignIdSweep.Fixture.write(null, List.of(
                        new ForeignIdSweep.Attempt(slots("id", orgB, "invitationId", invitationB)),
                        new ForeignIdSweep.Attempt(slots("id", orgA, "invitationId", invitationB)),
                        new ForeignIdSweep.Attempt(slots("id", orgB, "invitationId", invitationA))),
                        ForeignIdSweep.Control.of(slots("id", orgA, "invitationId", invitationA), null)),
                "DELETE /api/admin/invitations/{invitationId}", ForeignIdSweep.Fixture.write(null, List.of(),
                        new ForeignIdSweep.Control(slot("invitationId", staffInvitation), null, ForeignIdSweep.ADMIN)),
                "POST /api/admin/invitations", ForeignIdSweep.Fixture.write(
                        "{\"email\":\"sweep-staff@x.test\",\"roles\":[{\"role\":\"REVIEWER\",\"examCode\":\"IELTS\"}]}", List.of(),
                        new ForeignIdSweep.Control(Map.of(), null, ForeignIdSweep.ADMIN)),
                "POST /api/admin/organizations", ForeignIdSweep.Fixture.write("{\"name\":\"Sweep Org\",\"type\":\"CENTER\"}", List.of(),
                        new ForeignIdSweep.Control(Map.of(), null, ForeignIdSweep.ADMIN)),
                // a string slot: org B's real token, and one nobody issued
                "POST /api/invitations/{token}/accept", ForeignIdSweep.Fixture.write(null, List.of(
                        new ForeignIdSweep.Attempt(slot("token", tokenB)),
                        new ForeignIdSweep.Attempt(slot("token", "no-such-token-no-such-token-no-such-token-000"))),
                        ForeignIdSweep.Control.of(slot("token", tokenForA), null)));
    }

    private List<ForeignIdSweep.Attempt> foreignOrgsWithRoles(String ownBody) {
        return List.of(
                new ForeignIdSweep.Attempt(slot("id", orgB), ownBody),
                new ForeignIdSweep.Attempt(slot("id", orgB), "{\"userId\":\"" + userB + "\",\"roles\":[{\"role\":\"LEARNER\"}]}"),
                new ForeignIdSweep.Attempt(slot("id", orgA), "{\"userId\":\"" + userB + "\",\"roles\":[{\"role\":\"LEARNER\"}]}"));
    }

    // ---- the catalog of foreign rows -----------------------------------------------------------------------------------------------------------------

    /** Keys of org B's rows that org A may not see, uuid and text, plus everything the catalog walk could not classify or find. */
    public record Catalog(List<UUID> ids, List<String> texts, List<String> problems) {}

    /** Tables whose foreign ids are seeded explicitly above (they carry no tenant column of their own). */
    private static final Set<String> EXPLICIT = Set.of("users", "organizations");

    /** Tables with no tenant data a request can reach, each with its reason. The only list of its kind; a test may add to it through the argument of {@link #foreignIds}. */
    public static final Map<String, String> EXEMPT_TABLES = Map.ofEntries(
            Map.entry("jobs", "P1.7: the request role has INSERT only, no SELECT, and only the worker login reads it"),
            Map.entry("refresh_tokens", "P1.4: no privilege for the request role, only the SECURITY DEFINER auth_* functions touch it"),
            Map.entry("email_verifications", "P1.4: as refresh_tokens"),
            Map.entry("password_resets", "P1.4: as refresh_tokens"),
            Map.entry("notification_deliveries", "P1.8: RLS on with no policy, no privilege for the request role, only the delivery functions touch it"),
            Map.entry("event_publication", "P1.8: Spring Modulith's registry of module events: ids, no tenant column, no endpoint reads it"),
            Map.entry("exams", "reference data every tenant shares (IELTS, TOEIC), the same for everybody"));

    /**
     * Walks every public table, whatever its key looks like. A table must be either explicit ({@code users}, {@code organizations}), exempt with a reason,
     * or tenant-linked by {@code owner_org_id} / {@code org_id} / {@code source_org_id} / {@code user_id}; a tenant-linked table must hold at least one row
     * of org B. Every uuid and text column of its primary key and unique constraints is collected, so a composite or text key counts as well as
     * an {@code id uuid}. Anything else is a problem, so a table or endpoint added by a later task cannot be silently skipped: its author must seed a foreign
     * row here or write down why it is exempt.
     */
    public Catalog foreignIds(Map<String, String> exemptTables) {
        List<UUID> ids = new ArrayList<>(List.of(orgB, userB));
        List<String> texts = new ArrayList<>();
        List<String> problems = new ArrayList<>();
        List<String> tables = su.queryForList("""
                select t.table_name from information_schema.tables t
                 where t.table_schema = 'public' and t.table_type = 'BASE TABLE'
                   and t.table_name <> 'flyway_schema_history'
                 order by 1
                """, String.class);
        for (String t : tables) {
            if (EXPLICIT.contains(t) || EXEMPT_TABLES.containsKey(t) || exemptTables.containsKey(t)) {
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
                problems.add("table " + t + " has no owner_org_id/org_id/source_org_id/user_id: classify it or list it in the exempt tables with a reason");
                continue;
            }
            String where = String.join(" or ", conds);
            if (Boolean.FALSE.equals(su.queryForObject("select exists (select 1 from \"" + t + "\" where " + where + ")", Boolean.class, args.toArray()))) {
                problems.add("table " + t + " has no row belonging to org B: seed one in SweepFixtures so endpoints over it are really swept");
                continue;
            }
            List<Map<String, Object>> keys = su.queryForList("""
                    select distinct a.attname::text as name, format_type(a.atttypid, null) as type
                      from pg_index i join pg_attribute a on a.attrelid = i.indrelid and a.attnum = any (i.indkey)
                     where i.indrelid = format('public.%I', ?::text)::regclass and (i.indisprimary or i.indisunique)
                    """, t);
            boolean usable = false;
            for (Map<String, Object> key : keys) {
                String name = (String) key.get("name");
                String type = (String) key.get("type");
                String select = "select distinct \"" + name + "\"::text from \"" + t + "\" where \"" + name + "\" is not null and (" + where + ")";
                if ("uuid".equals(type)) {
                    usable = true;
                    su.queryForList(select, String.class, args.toArray()).forEach(v -> ids.add(UUID.fromString(v)));
                } else if ("text".equals(type) || type.startsWith("character")) {
                    usable = true;
                    texts.addAll(su.queryForList(select, String.class, args.toArray()));
                }
            }
            if (!usable) {
                problems.add("table " + t + " has no uuid or text key the walk can use: classify it or list it in the exempt tables with a reason");
            }
        }
        return new Catalog(ids.stream().distinct().toList(), texts.stream().distinct().toList(), problems);
    }
}
