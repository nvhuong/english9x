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
    /** An AUTHOR of IELTS in org A, with a media file of org A: the positive control of the media routes (the sweep's caller is an ORG_ADMIN, who is no author). */
    public final UUID authorA = UUID.randomUUID();
    public final UUID mediaA = UUID.randomUUID();
    /** P2.3: org B's question and group (with their versions), and A's author's own drafts: the positive controls of PUT and submit (each changes its own). */
    public final UUID questionB = UUID.randomUUID();
    public final UUID questionVersionB = UUID.randomUUID();
    public final UUID groupB = UUID.randomUUID();
    public final UUID groupVersionB = UUID.randomUUID();
    public final UUID questionAForPut = UUID.randomUUID();
    /** The version of {@link #questionAForPut}: a member A's groups may list. */
    public final UUID questionVersionA = UUID.randomUUID();
    public final UUID questionAForSubmit = UUID.randomUUID();
    public final UUID importA = UUID.randomUUID();
    public final UUID importB = UUID.randomUUID();
    public final UUID groupAForPut = UUID.randomUUID();
    /** The version of {@link #groupAForPut}: a group version A's tests may list. */
    public final UUID groupVersionA = UUID.randomUUID();
    /** P2.6: org B's test (with a version, a part and a group), and A's author's own draft tests: the positive controls of PUT structure. */
    public final UUID testB = UUID.randomUUID();
    public final UUID testAForPut = UUID.randomUUID();
    public final UUID testAForSubmit = UUID.randomUUID();
    public final UUID groupAForSubmit = UUID.randomUUID();
    /** P2.4: org B's strategy lesson (PUBLISHED, with a question type), and A's author's own drafts: the positive controls of PUT and submit. */
    public final UUID lessonB = UUID.randomUUID();
    public final UUID lessonVersionB = UUID.randomUUID();
    public final UUID lessonAForPut = UUID.randomUUID();
    public final UUID lessonAForSubmit = UUID.randomUUID();
    /** P2.5: A's author's own draft grading skills (one per route that changes a skill): the positive controls of PUT and submit. */
    public final UUID skillAForPut = UUID.randomUUID();
    public final UUID skillAForSubmit = UUID.randomUUID();
    public final UUID mediaB = UUID.randomUUID();
    public final UUID invitationA = UUID.randomUUID();
    public final UUID invitationB = UUID.randomUUID();
    public final UUID staffInvitation = UUID.randomUUID();
    public final UUID notificationA = UUID.randomUUID();
    public final String courseCodeB = "B00001";
    /** P5.1: a plan on sale, a published English9x course, and an order of A's member and one of B's member (the order code is a text key of the walk). */
    public final UUID planId = UUID.randomUUID();
    public final UUID publishedCourse = UUID.randomUUID();
    /** P5.3: an ACTIVE licence of each organisation over {@link #publishedCourse}; A's has a seat held by {@link #holderA} (the positive control of the revoke route). */
    public final UUID licenseA = UUID.randomUUID();
    public final UUID licenseB = UUID.randomUUID();
    public final UUID holderA = UUID.randomUUID();
    public final String orderCodeA = "SWA001";
    public final String orderCodeB = "SWB001";
    /** P5.2: two more pending orders of A's member, one for the confirm route's positive control and one for the reject route's (each decision changes its order). */
    public final String orderCodeConfirmA = "SWA002";
    public final String orderCodeRejectA = "SWA003";
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
        // P2.1: org B's catalog rows (a score table with a published version, a grading skill with a published version, a grading policy);
        // no catalog endpoint takes an id yet, P2.5 adds them and the sweep then covers them
        UUID scoreTableB = UUID.randomUUID();
        // an organisation's score table can only be TEST scope (P2.6): a real test of org B to hang it on
        su.update("insert into tests(id,owner_org_id,exam_code,created_by) values (?,?,'IELTS',?)", testB, orgB, userB);
        su.update("insert into score_tables(id,owner_org_id,exam_code,skill,scope,test_id) values (?,?,'IELTS','LISTENING','TEST',?)", scoreTableB, orgB, testB);
        su.update("insert into score_table_versions(score_table_id,owner_org_id,exam_code,version_no,status) values (?,?,'IELTS',1,'PUBLISHED')", scoreTableB, orgB);
        UUID skillB = UUID.randomUUID();
        su.update("insert into grading_skills(id,owner_org_id,exam_code,question_type_code) values (?,?,'IELTS','IELTS_W_TASK2')", skillB, orgB);
        su.update("insert into grading_skill_versions(grading_skill_id,owner_org_id,exam_code,version_no,status) values (?,?,'IELTS',1,'PUBLISHED')", skillB, orgB);
        su.update("insert into grading_policies(owner_org_id,exam_code,scope,mode) values (?,'IELTS','ORG','BOTH')", orgB);
        // P2.2: org B's media file (the url endpoint takes its id; A's ORG_ADMIN must get neither a link nor a different answer than for an id nobody has) and an
        // author of org A with a file of A for the positive control
        su.update("insert into users(id,email,email_verified_at,consent_at) values (?,'author-a@x.test',now(),now())", authorA);
        su.update("insert into org_memberships(org_id,user_id) values (?,?)", orgA, authorA);
        su.update("insert into role_grants(user_id,org_id,exam_code,role) values (?,?,'IELTS','AUTHOR')", authorA, orgA);
        String media = "insert into media_assets(id,owner_org_id,exam_code,kind,mime_type,size_bytes,duration_ms,original_filename,uploaded_by) "
                + "values (?,?,'IELTS','AUDIO','audio/mpeg',1000,1500,'sweep.mp3',?)";
        su.update(media, mediaB, orgB, userB);
        su.update(media, mediaA, orgA, authorA);
        // P2.3: org B's question and group, each with a PUBLISHED version (the group with a member); A's author owns two draft questions and two draft groups, one per
        // positive control (a PUT or a submit changes the draft it is aimed at)
        String q = "insert into questions(id,owner_org_id,exam_code,type_code,created_by) values (?,?,'IELTS','IELTS_R_SHORT_ANSWER',?)";
        String qv = "insert into question_versions(id,question_id,owner_org_id,exam_code,type_code,version_no,status,content,answer_key,created_by) "
                + "values (?,?,?,'IELTS','IELTS_R_SHORT_ANSWER',1,?,'{\"stem\":\"s\",\"prompt\":\"p\"}','{\"slots\":[[\"k\"]]}',?)";
        su.update(q, questionB, orgB, userB);
        su.update(qv, questionVersionB, questionB, orgB, "PUBLISHED", userB);
        su.update("insert into question_groups(id,owner_org_id,exam_code,created_by) values (?,?,'IELTS',?)", groupB, orgB, userB);
        su.update("insert into question_group_versions(id,group_id,owner_org_id,exam_code,version_no,status,content,created_by) values (?,?,?,'IELTS',1,'DRAFT','{\"title\":\"b\"}',?)",
                groupVersionB, groupB, orgB, userB);
        su.update("insert into question_group_items(group_version_id,position,question_version_id,owner_org_id,exam_code) values (?,1,?,?,'IELTS')", groupVersionB, questionVersionB, orgB);
        su.update("update question_group_versions set status = 'IN_REVIEW' where id = ?", groupVersionB);
        su.update("update question_group_versions set status = 'PUBLISHED' where id = ?", groupVersionB);
        for (UUID own : List.of(questionAForPut, questionAForSubmit)) {
            su.update(q, own, orgA, authorA);
            su.update(qv, own.equals(questionAForPut) ? questionVersionA : UUID.randomUUID(), own, orgA, "DRAFT", authorA);
        }
        for (UUID own : List.of(groupAForPut, groupAForSubmit)) {
            su.update("insert into question_groups(id,owner_org_id,exam_code,created_by) values (?,?,'IELTS',?)", own, orgA, authorA);
            su.update("insert into question_group_versions(id,group_id,owner_org_id,exam_code,version_no,status,content,created_by) values (?,?,?,'IELTS',1,'DRAFT','{\"title\":\"a\"}',?)",
                    own.equals(groupAForPut) ? groupVersionA : UUID.randomUUID(), own, orgA, authorA);
        }
        // P2.7: org B's import (a finished one), and A's author's finished import with one draft (the positive control of the submit route)
        su.update("insert into import_jobs(id,owner_org_id,user_id,exam_code,type_code,status,source_name,created_count,total_rows,finished_at) values (?,?,?,'IELTS','IELTS_R_SHORT_ANSWER','SUCCEEDED','b.xlsx',0,0,now())",
                importB, orgB, userB);
        UUID importedQuestion = UUID.randomUUID();
        UUID importedVersion = UUID.randomUUID();
        su.update(q, importedQuestion, orgA, authorA);
        su.update(qv, importedVersion, importedQuestion, orgA, "DRAFT", authorA);
        su.update("insert into import_jobs(id,owner_org_id,user_id,exam_code,type_code,status,source_name,created_count,total_rows,finished_at) values (?,?,?,'IELTS','IELTS_R_SHORT_ANSWER','SUCCEEDED','a.xlsx',1,1,now())",
                importA, orgA, authorA);
        su.update("insert into import_job_versions(import_job_id,position,row_no,question_version_id) values (?,1,2,?)", importA, importedVersion);
        // P2.6: org B's test (a DRAFT version with a Listening part holding B's group), and two draft tests of A's author (no parts yet)
        UUID testVersionB = UUID.randomUUID();
        su.update("insert into test_versions(id,test_id,owner_org_id,exam_code,variant,version_no,status,title,created_by) values (?,?,?,'IELTS','AC',1,'DRAFT','B test',?)", testVersionB, testB, orgB, userB);
        su.update("insert into test_parts(test_version_id,position,section_code,duration_sec,owner_org_id,exam_code) values (?,1,'LISTENING',1800,?,'IELTS')", testVersionB, orgB);
        su.update("insert into test_part_groups(test_version_id,part_position,position,group_version_id,owner_org_id,exam_code) values (?,1,1,?,?,'IELTS')", testVersionB, groupVersionB, orgB);
        for (UUID own : List.of(testAForPut, testAForSubmit)) {
            su.update("insert into tests(id,owner_org_id,exam_code,variant,created_by) values (?,?,'IELTS','AC',?)", own, orgA, authorA);
            su.update("insert into test_versions(id,test_id,owner_org_id,exam_code,variant,version_no,title,created_by) values (?,?,?,'IELTS','AC',1,'A test',?)", UUID.randomUUID(), own, orgA, authorA);
        }
        // P2.4: strategy lessons, same shape: B's published lesson (with its type), and two draft lessons of A's author
        String lesson = "insert into strategy_lessons(id,owner_org_id,exam_code,created_by) values (?,?,'IELTS',?)";
        String lessonVersion = "insert into strategy_lesson_versions(id,lesson_id,owner_org_id,exam_code,version_no,title,blocks,created_by) "
                + "values (?,?,?,'IELTS',1,'Sweep lesson','[{\"type\":\"paragraph\",\"text\":\"x\"}]',?)";
        String lessonType = "insert into strategy_lesson_types(version_id,type_code,owner_org_id,exam_code) values (?,'IELTS_R_SHORT_ANSWER',?,'IELTS')";
        su.update(lesson, lessonB, orgB, userB);
        su.update(lessonVersion, lessonVersionB, lessonB, orgB, userB);
        su.update(lessonType, lessonVersionB, orgB);
        su.update("update strategy_lesson_versions set status = 'IN_REVIEW' where id = ?", lessonVersionB);
        su.update("update strategy_lesson_versions set status = 'PUBLISHED' where id = ?", lessonVersionB);
        for (UUID own : List.of(lessonAForPut, lessonAForSubmit)) {
            UUID version = UUID.randomUUID();
            su.update(lesson, own, orgA, authorA);
            su.update(lessonVersion, version, own, orgA, authorA);
            su.update(lessonType, version, orgA);
        }
        // P2.5: grading skills are owned like lessons (the bound context); two draft skills of A's author for two other Writing types than the one POST creates
        String skill = "insert into grading_skills(id,owner_org_id,exam_code,question_type_code) values (?,?,'IELTS',?)";
        String skillVersion = "insert into grading_skill_versions(id,grading_skill_id,owner_org_id,exam_code,version_no,created_by) values (?,?,?,'IELTS',1,?)";
        su.update(skill, skillAForPut, orgA, "IELTS_W_AC_TASK1");
        su.update(skillVersion, UUID.randomUUID(), skillAForPut, orgA, authorA);
        su.update(skill, skillAForSubmit, orgA, "IELTS_W_GT_TASK1");
        su.update(skillVersion, UUID.randomUUID(), skillAForSubmit, orgA, authorA);
        for (UUID course : su.queryForList("select id from courses where owner_org_id = ?", UUID.class, orgB)) {
            com.english9x.support.SeatFixtures.seatEntitlement(su, orgB, userB, course, java.time.Instant.now().minus(java.time.Duration.ofDays(1)), java.time.Instant.now().plus(java.time.Duration.ofDays(1)));
        }
        // P5.1: plans are a price list shared by everybody; orders belong to a person (B's member has one, with an item; A's member has one too, the positive control)
        su.update("insert into plans(id,name,price_vnd,months,max_courses) values (?,'Sweep plan',100000,1,2)", planId);
        su.update("insert into courses(id,owner_org_id,exam_code,code,title,status) values (?,null,'IELTS','SWPUB1','Published','PUBLISHED')", publishedCourse);
        for (Object[] o : new Object[][] {{orderCodeA, userA}, {orderCodeB, userB}, {orderCodeConfirmA, userA}, {orderCodeRejectA, userA}}) {
            UUID order = UUID.randomUUID();
            su.update("insert into orders(id,code,user_id,plan_id,plan_months,amount_vnd) values (?,?,?,?,1,100000)", order, o[0], o[1], planId);
            su.update("insert into order_items(order_id,user_id,course_id,course_code) values (?,?,?,'SWPUB1')", order, o[1], publishedCourse);
        }
        // P5.3: a licence of each organisation (ACTIVE, 5 seats, the published course); B's member holds a seat of B's, A's holder one of A's
        for (Object[] l : new Object[][] {{licenseA, orgA}, {licenseB, orgB}}) {
            su.update("insert into org_licenses(id,org_id,seats,months,price_vnd,activation,contract_no,status,valid_from,valid_until,created_by) "
                    + "values (?,?,5,12,1000,'CONTRACT','SWEEP','ACTIVE',now() - interval '1 day',now() + interval '300 days',?)", l[0], l[1], admin);
            su.update("insert into org_license_courses(license_id,org_id,course_id) values (?,?,?)", l[0], l[1], publishedCourse);
        }
        su.update("insert into users(id,email,email_verified_at,consent_at) values (?,'holder-a@x.test',now(),now())", holderA);
        su.update("insert into org_memberships(org_id,user_id) values (?,?)", orgA, holderA);
        su.update("insert into seat_assignments(license_id,org_id,user_id,assigned_by) values (?,?,?,?),(?,?,?,?)", licenseA, orgA, holderA, userA, licenseB, orgB, userB, userB);
        // P5.2: the receiving account (one active row, shared by everybody who pays)
        su.update("insert into payment_accounts(bank_bin,account_no,account_name) values ('970436','SWEEP123456','Sweep account')");
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
            Map.entry("DELETE /api/me/telegram", OWN),
            Map.entry("POST /api/cms/import", "P2.7: takes no id of a tenant: the owner is the bound context, the type a query parameter, the files the body; refused 403 for anybody who is no AUTHOR of the exam in that context (ImportJobIT.onlyAnAuthorOfTheExamImportsAndOnlyItsTypes). The job it queues acts as the uploader under the same policies"),
            Map.entry("POST /api/orders", OWN + " (the plan and the courses are shared catalogue rows, not a tenant's; OrderServiceIT covers who may order what)"));
    // (POST /api/cms/media is not exempt: it has a typed fixture below, with a multipart body)

    /** A minimal valid invitation: what makes the answer depend on who asks, not on the body. */
    private static final String INVITE = "{\"email\":\"sweep-new@x.test\",\"roles\":[{\"role\":\"LEARNER\"}]}";

    /** A body the IT turns into a multipart upload of a real PNG for IELTS (see RlsLeakSweepIT#call). */
    public static final String MULTIPART_PNG = "MULTIPART:IELTS:pixel.png";

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
        Map<String, ForeignIdSweep.Fixture> all = new java.util.HashMap<>(Map.of(
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
                        ForeignIdSweep.Control.of(slot("token", tokenForA), null))));
        // P2.2: the upload takes no id of a tenant (the owner is the bound context, the exam a query parameter). The sweep's caller is an ORG_ADMIN with no
        // AUTHOR role: refused; the author of org A in org A's context really uploads. "MULTIPART:" is turned into a multipart request by the IT.
        all.put("POST /api/cms/media", ForeignIdSweep.Fixture.write(MULTIPART_PNG, List.of(), new ForeignIdSweep.Control(Map.of(), null, ForeignIdSweep.AUTHOR)));
        // P5.1: an order is the caller's own (user_id = the person), never an organisation's. A's member really orders; the plan routes are the English9x SYS_ADMIN's
        // (A's ORG_ADMIN is refused with 403 whatever the id); an order code of B's member is a 404 as much as a code nobody has.
        String plan = "{\"name\":\"Sweep\",\"priceVnd\":1000,\"months\":1,\"maxCourses\":1,\"active\":true}";
        all.put("POST /api/admin/plans", ForeignIdSweep.Fixture.write(plan, List.of(), new ForeignIdSweep.Control(Map.of(), null, ForeignIdSweep.ADMIN)));
        all.put("PUT /api/admin/plans/{id}", ForeignIdSweep.Fixture.write(plan, List.of(), new ForeignIdSweep.Control(slot("id", planId), null, ForeignIdSweep.ADMIN)));
        all.put("GET /api/orders/{code}", ForeignIdSweep.Fixture.read(List.of(
                new ForeignIdSweep.Attempt(slot("code", orderCodeB)), new ForeignIdSweep.Attempt(slot("code", "NOSUCH")))));
        // P5.2: the administrators' routes. A's ORG_ADMIN is refused with 403 whatever the code; the SYS_ADMIN control really decides A's member's own pending order.
        // The QR image is the owner's: B's member's code and a code nobody has are 404 (the positive control, A's member's own pending order, is asserted in RlsLeakSweepIT).
        all.put("PUT /api/admin/payment-account", ForeignIdSweep.Fixture.write("{\"bankBin\":\"970436\",\"accountNo\":\"SWEEP123456\",\"accountName\":\"Sweep account\"}", List.of(),
                new ForeignIdSweep.Control(Map.of(), null, ForeignIdSweep.ADMIN)));
        all.put("POST /api/admin/orders/{code}/confirm", ForeignIdSweep.Fixture.write("{\"bankRef\":\"SWEEP\"}", List.of(
                new ForeignIdSweep.Attempt(slot("code", orderCodeB)), new ForeignIdSweep.Attempt(slot("code", "NOSUCH"))),
                new ForeignIdSweep.Control(slot("code", orderCodeConfirmA), null, ForeignIdSweep.ADMIN)));
        all.put("POST /api/admin/orders/{code}/reject", ForeignIdSweep.Fixture.write("{\"reason\":\"sweep\"}", List.of(
                new ForeignIdSweep.Attempt(slot("code", orderCodeB)), new ForeignIdSweep.Attempt(slot("code", "NOSUCH"))),
                new ForeignIdSweep.Control(slot("code", orderCodeRejectA), null, ForeignIdSweep.ADMIN)));
        // P5.3: licences. A's ORG_ADMIN is refused with 403 on the SYS_ADMIN's creation route whatever the body; the SYS_ADMIN control really creates one for A. Seats: the
        // organisation and the licence are both walked (each foreign, and mixed with the caller's own), the control is A's own licence and A's own member.
        String licenseBody = "{\"orgId\":\"" + orgA + "\",\"seats\":2,\"months\":3,\"courseIds\":[\"" + publishedCourse + "\"],\"priceVnd\":1000000,\"activation\":\"CONTRACT\",\"contractNo\":\"SW-1\"}";
        all.put("POST /api/admin/org-licenses", ForeignIdSweep.Fixture.write(licenseBody, List.of(), new ForeignIdSweep.Control(Map.of(), null, ForeignIdSweep.ADMIN)));
        String seatBody = "{\"userId\":\"" + memberA + "\",\"courseIds\":[\"" + publishedCourse + "\"]}";
        all.put("POST /api/orgs/{orgId}/licenses/{id}/seats", ForeignIdSweep.Fixture.write(seatBody, List.of(
                new ForeignIdSweep.Attempt(slots("orgId", orgB, "id", licenseB)),
                new ForeignIdSweep.Attempt(slots("orgId", orgA, "id", licenseB)),
                new ForeignIdSweep.Attempt(slots("orgId", orgB, "id", licenseA))),
                ForeignIdSweep.Control.of(slots("orgId", orgA, "id", licenseA), seatBody)));
        all.put("DELETE /api/orgs/{orgId}/licenses/{id}/seats/{userId}", ForeignIdSweep.Fixture.write(null, List.of(
                new ForeignIdSweep.Attempt(Map.of("orgId", orgB.toString(), "id", licenseB.toString(), "userId", userB.toString())),
                new ForeignIdSweep.Attempt(Map.of("orgId", orgA.toString(), "id", licenseB.toString(), "userId", userB.toString())),
                new ForeignIdSweep.Attempt(Map.of("orgId", orgB.toString(), "id", licenseA.toString(), "userId", holderA.toString())),
                new ForeignIdSweep.Attempt(Map.of("orgId", orgA.toString(), "id", licenseA.toString(), "userId", userB.toString()))),
                ForeignIdSweep.Control.of(Map.of("orgId", orgA.toString(), "id", licenseA.toString(), "userId", holderA.toString()), null)));
        all.put("GET /api/orders/{code}/qr.png", ForeignIdSweep.Fixture.read(List.of(
                new ForeignIdSweep.Attempt(slot("code", orderCodeB)), new ForeignIdSweep.Attempt(slot("code", "NOSUCH")))));
        // P2.3: questions and groups. The caller (A's ORG_ADMIN, no AUTHOR grant) is refused on every route and every foreign id; A's author really writes
        // A's own drafts (one per route, because each write changes the draft it is aimed at). The foreign ids are walked on the routes with one uuid.
        String question = questionBody();
        String group = "{\"examCode\":\"IELTS\",\"content\":{\"title\":\"Sweep\"},\"questionVersionIds\":[\"" + questionVersionA + "\"]}";
        ForeignIdSweep.Control none = new ForeignIdSweep.Control(Map.of(), null, ForeignIdSweep.AUTHOR);
        all.put("POST /api/cms/questions", ForeignIdSweep.Fixture.write(question, List.of(), none));
        all.put("PUT /api/cms/questions/{id}", ForeignIdSweep.Fixture.write(question, List.of(), new ForeignIdSweep.Control(slot("id", questionAForPut), null, ForeignIdSweep.AUTHOR)));
        all.put("POST /api/cms/questions/{id}/submit", ForeignIdSweep.Fixture.write(null, List.of(), new ForeignIdSweep.Control(slot("id", questionAForSubmit), null, ForeignIdSweep.AUTHOR)));
        // P2.6: tests, like groups: A's ORG_ADMIN (no AUTHOR grant) is refused on every route and every foreign id; A's author really builds A's draft tests
        String testBody = "{\"examCode\":\"IELTS\",\"variant\":\"AC\",\"title\":\"Sweep test\"}";
        String structureBody = "{\"parts\":[{\"sectionCode\":\"READING\",\"durationSec\":600,\"groups\":[\"" + groupVersionA + "\"]}]}";
        all.put("POST /api/cms/tests", ForeignIdSweep.Fixture.write(testBody, List.of(), none));
        all.put("PUT /api/cms/tests/{id}/structure", ForeignIdSweep.Fixture.write(structureBody, List.of(), new ForeignIdSweep.Control(slot("id", testAForPut), null, ForeignIdSweep.AUTHOR)));
        all.put("POST /api/cms/tests/{id}/submit", new ForeignIdSweep.Fixture(null, List.of(), null,
                "a test goes to review only when every skill of its exam has questions (IELTS: four, each with a published group); TestServiceIT.aTestWithoutASkillIsNotSentForReview... measures the 202 and the refusals, the foreign ids are walked here"));
        // P2.7: the upload takes no id of a tenant (the owner is the bound context, the type a query parameter); the submit takes the import's id: B's import is a 404
        // for A's people, A's author really submits A's own. The template is a catalogue item, the same for every tenant: the ORG_ADMIN without AUTHOR is refused.
        all.put("POST /api/cms/import/{jobId}/submit", ForeignIdSweep.Fixture.write(null, List.of(), new ForeignIdSweep.Control(slot("jobId", importA), null, ForeignIdSweep.AUTHOR)));
        all.put("GET /api/cms/import/templates/{typeCode}.xlsx", ForeignIdSweep.Fixture.read(List.of(new ForeignIdSweep.Attempt(slot("typeCode", "IELTS_R_SHORT_ANSWER")))));
        all.put("POST /api/cms/question-groups", ForeignIdSweep.Fixture.write(group, List.of(), none));
        all.put("PUT /api/cms/question-groups/{id}", ForeignIdSweep.Fixture.write(group, List.of(), new ForeignIdSweep.Control(slot("id", groupAForPut), null, ForeignIdSweep.AUTHOR)));
        all.put("POST /api/cms/question-groups/{id}/submit", ForeignIdSweep.Fixture.write(null, List.of(), new ForeignIdSweep.Control(slot("id", groupAForSubmit), null, ForeignIdSweep.AUTHOR)));
        // P2.4: strategy lessons, like questions: A's ORG_ADMIN (no AUTHOR grant) is refused on every route and every foreign id; A's author really writes A's drafts
        String lessonBody = "{\"examCode\":\"IELTS\",\"title\":\"Sweep\",\"blocks\":[{\"type\":\"paragraph\",\"text\":\"x\"}],\"questionTypeCodes\":[\"IELTS_R_SHORT_ANSWER\"]}";
        all.put("POST /api/cms/strategy-lessons", ForeignIdSweep.Fixture.write(lessonBody, List.of(), none));
        all.put("PUT /api/cms/strategy-lessons/{id}", ForeignIdSweep.Fixture.write(lessonBody, List.of(), new ForeignIdSweep.Control(slot("id", lessonAForPut), null, ForeignIdSweep.AUTHOR)));
        all.put("POST /api/cms/strategy-lessons/{id}/submit", ForeignIdSweep.Fixture.write(null, List.of(), new ForeignIdSweep.Control(slot("id", lessonAForSubmit), null, ForeignIdSweep.AUTHOR)));
        // P2.5: grading skills, like lessons: A's ORG_ADMIN (no AUTHOR grant) is refused on every route and every foreign id; A's author really writes A's drafts.
        // The dry run answers 501 until the AI grader exists (P4.2), so it has no 2xx control; the ids it walks are checked like the others.
        String criteria = "[{\"code\":\"task_response\",\"name\":\"Task response\",\"min\":0,\"max\":9,\"step\":0.5}]";
        String skillRest = "\"rubric\":\"r\",\"criteria\":" + criteria + ",\"promptTemplate\":\"{{rubric}} {{samples}} {{task}} {{response}}\",\"samples\":[],"
                + "\"resultSchema\":{\"type\":\"object\",\"properties\":{\"task_response\":{\"type\":\"number\"}},\"required\":[\"task_response\"]}";
        all.put("POST /api/cms/grading-skills", ForeignIdSweep.Fixture.write("{\"questionTypeCode\":\"IELTS_W_TASK2\"," + skillRest + "}", List.of(), none));
        all.put("PUT /api/cms/grading-skills/{id}", ForeignIdSweep.Fixture.write("{" + skillRest + "}", List.of(), new ForeignIdSweep.Control(slot("id", skillAForPut), null, ForeignIdSweep.AUTHOR)));
        all.put("POST /api/cms/grading-skills/{id}/submit", ForeignIdSweep.Fixture.write(null, List.of(), new ForeignIdSweep.Control(slot("id", skillAForSubmit), null, ForeignIdSweep.AUTHOR)));
        all.put("POST /api/cms/grading-skills/{id}/dry-run", new ForeignIdSweep.Fixture("{\"response\":\"x\"}", List.of(), null,
                "answers 501 whoever asks until the AI grader exists (P4.2); GradingSkillIT.dryRunWaitsForTheAiGrader measures who is refused before that"));
        // P2.5: the conversion table of an exam is English9x's (owner NULL, scope EXAM): the sweep's callers are all in organisation contexts and are refused (403)
        // whatever the id, so there is no 2xx control to give; ScoreTableServiceIT measures the English9x staff's own writes and every refusal.
        String englishNine = "an exam default belongs to English9x: a caller in an organisation's context is always refused; ScoreTableServiceIT.onlyTheStaffOfTheExamWriteAndRead measures it";
        String table = "{\"examCode\":\"IELTS\",\"skill\":\"LISTENING\",\"scope\":\"EXAM\",\"rows\":[{\"raw\":0,\"scaled\":0},{\"raw\":1,\"scaled\":4.5}]}";
        all.put("POST /api/cms/score-tables", new ForeignIdSweep.Fixture(table, List.of(), null, englishNine));
        all.put("PUT /api/cms/score-tables/{id}", new ForeignIdSweep.Fixture(table, List.of(), null, englishNine));
        all.put("POST /api/cms/score-tables/{id}/submit", new ForeignIdSweep.Fixture(null, List.of(), null, englishNine));
        return all;
    }

    /** The valid example of IELTS_R_SHORT_ANSWER (no file ids) as the body of a question. */
    private static String questionBody() {
        try {
            com.fasterxml.jackson.databind.node.ObjectNode example = (com.fasterxml.jackson.databind.node.ObjectNode) new com.fasterxml.jackson.databind.ObjectMapper()
                    .readTree(java.nio.file.Path.of("src/test/resources/catalog/examples/IELTS_R_SHORT_ANSWER.valid.json").toFile());
            example.put("typeCode", "IELTS_R_SHORT_ANSWER");
            return example.toString();
        } catch (java.io.IOException e) {
            throw new IllegalStateException(e);
        }
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
            Map.entry("plans", "P5.1: the price list, the same for every tenant; the request role reads active plans and only a real SYS_ADMIN writes (OrderServiceIT.planAdminIsSysAdminOnly...)"),
            Map.entry("payment_accounts", "P5.2: the one bank account every payer pays into, the same for every tenant; the request role reads the active row and only a real SYS_ADMIN writes it (OrderAdminIT.paymentAccountAdmin..., 43_payment_accounts.sql)"),
            Map.entry("exams", "reference data every tenant shares (IELTS, TOEIC), the same for everybody; read-only for the application (P2.1)"),
            Map.entry("exam_sections", "P2.1: reference data of the catalog, the same for everybody, read-only for the application"),
            Map.entry("interaction_kinds", "P2.1: reference data of the catalog, the same for everybody, read-only for the application"),
            Map.entry("import_errors", "P2.7: no tenant column of its own; the request role reads and writes a row only while its import (import_jobs, swept) is visible to it, and no endpoint takes the id of an error"),
            Map.entry("import_job_versions", "P2.7: as import_errors: the drafts of a lot, visible only with their import; no endpoint takes their ids"),
            Map.entry("question_types", "P2.1: reference data of the catalog, the same for everybody, read-only for the application"));

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
