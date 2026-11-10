package com.english9x.workflow;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.english9x.org.api.CurrentContextHolder;
import com.english9x.support.TestPostgres;
import com.english9x.workflow.api.BundleRef;
import com.english9x.workflow.api.ContentStatus;
import com.english9x.workflow.api.InvalidReviewInputException;
import com.english9x.workflow.api.ReviewQueueEntry;
import com.english9x.workflow.api.ReviewConflictException;
import com.english9x.workflow.api.ReviewNotFoundException;
import com.english9x.workflow.api.ReviewService;
import com.english9x.workflow.api.ReviewView;
import com.english9x.workflow.api.SelfReviewException;
import com.english9x.workflow.api.UnpublishedDependencyException;
import com.english9x.workflow.api.VersionRef;
import com.english9x.workflow.api.events.ContentPublished;
import com.english9x.workflow.api.events.ReviewApproved;
import com.english9x.workflow.api.events.ReviewChangesRequested;
import com.english9x.workflow.api.events.ReviewRequested;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.Callable;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.CyclicBarrier;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.function.Supplier;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.event.TransactionPhase;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.transaction.event.TransactionalEventListener;
import org.springframework.context.event.EventListener;
import org.springframework.transaction.support.TransactionSynchronizationManager;

/**
 * The review workflow against the real database and the real context binding (RLS, role data, locks), over a test-only
 * content type. Nobody here is a mock: a user "is" whoever {@link CurrentContextHolder} is bound to.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.NONE)
@Import(ReviewServiceIT.Config.class)
class ReviewServiceIT {

    static final String DB = TestPostgres.createDatabase();

    static final int MAX_BUNDLE = 130;
    static final UUID ORG_C = UUID.randomUUID();           // its own organisation: a queue nobody else filled
    static final UUID AUTHOR_C = UUID.randomUUID();
    static final UUID REVIEWER_C = UUID.randomUUID();
    static final UUID ORG_D = UUID.randomUUID();           // the audit-log tests' own organisation, so they leave ORG_C's queue as it was
    static final UUID AUTHOR_D = UUID.randomUUID();
    static final UUID REVIEWER_D = UUID.randomUUID();
    static final UUID ORG_A = UUID.randomUUID();
    static final UUID ORG_B = UUID.randomUUID();
    static final UUID AUTHOR_A = UUID.randomUUID();       // AUTHOR IELTS in A
    static final UUID AUTHOR_A_BOTH = UUID.randomUUID();  // AUTHOR IELTS + TOEIC in A
    static final UUID REVIEWER_A = UUID.randomUUID();     // REVIEWER IELTS in A
    static final UUID REVIEWER_A2 = UUID.randomUUID();    // REVIEWER IELTS in A
    static final UUID REVIEWER_A_TOEIC = UUID.randomUUID();
    static final UUID DUAL_A = UUID.randomUUID();         // AUTHOR + REVIEWER IELTS in A
    static final UUID LEARNER_A = UUID.randomUUID();
    static final UUID AUTHOR_B = UUID.randomUUID();
    static final UUID REVIEWER_B = UUID.randomUUID();
    static final UUID E9_AUTHOR = UUID.randomUUID();      // English9x AUTHOR IELTS
    static final UUID E9_REVIEWER = UUID.randomUUID();    // English9x REVIEWER IELTS, also a member of A
    static final UUID SYS_DUAL = UUID.randomUUID();       // English9x SYS_ADMIN + AUTHOR + REVIEWER IELTS

    @DynamicPropertySource
    static void database(DynamicPropertyRegistry registry) {
        TestPostgres.connect(registry, DB);
        registry.add("e9x.workflow.max-bundle-size", () -> String.valueOf(MAX_BUNDLE));
        registry.add("e9x.content.import.max-rows", () -> String.valueOf(MAX_BUNDLE)); // P2.7: an import lot must fit one bundle, and the application refuses to start when it cannot
    }

    @TestConfiguration
    static class Config {
        @Bean
        TestItems testItems(JdbcTemplate jdbc) {
            return new TestItems(jdbc);
        }

        @Bean
        Recorder recorder() {
            return new Recorder();
        }
    }

    static class Recorder {
        final List<ContentPublished> committed = new CopyOnWriteArrayList<>();
        final List<Boolean> publishedInsideTransaction = new CopyOnWriteArrayList<>();
        final List<Object> workflowEvents = new CopyOnWriteArrayList<>();

        @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT)
        void requested(ReviewRequested e) {
            workflowEvents.add(e);
        }

        @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT)
        void changes(ReviewChangesRequested e) {
            workflowEvents.add(e);
        }

        @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT)
        void approved(ReviewApproved e) {
            workflowEvents.add(e);
        }

        @EventListener
        void now(ContentPublished e) {
            publishedInsideTransaction.add(TransactionSynchronizationManager.isActualTransactionActive());
        }

        @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT)
        void afterCommit(ContentPublished e) {
            committed.add(e);
        }

        // Accessors, not field reads: since P1.8 the event registry proxies every bean that has a transactional listener, and a
        // field read on the proxy would see its own (null) field instead of this object's.
        List<ContentPublished> committed() {
            return committed;
        }

        List<Boolean> publishedInsideTransaction() {
            return publishedInsideTransaction;
        }

        List<Object> workflowEvents() {
            return workflowEvents;
        }

        List<ContentPublished> forVersion(UUID id) {
            return committed.stream().filter(e -> e.versionId().equals(id)).toList();
        }
    }

    @Autowired ReviewService reviews;
    @Autowired TestItems items;
    @Autowired Recorder recorder;
    @Autowired JdbcTemplate appJdbc;
    @Autowired PlatformTransactionManager tm;
    static JdbcTemplate su;

    @BeforeAll
    static void fixtures(@Autowired JdbcTemplate migrated) {
        su = new JdbcTemplate(TestPostgres.superuser(DB));
        su.update("insert into exams(code,name) values ('IELTS','IELTS'),('TOEIC','TOEIC') on conflict do nothing");
        su.update("insert into organizations(id,name,type) values (?,'A','CENTER'),(?,'B','CENTER'),(?,'C','CENTER'),(?,'D','CENTER')", ORG_A, ORG_B, ORG_C, ORG_D);
        for (UUID u : new UUID[] {AUTHOR_A, AUTHOR_A_BOTH, REVIEWER_A, REVIEWER_A2, REVIEWER_A_TOEIC, DUAL_A, LEARNER_A, AUTHOR_B,
                REVIEWER_B, E9_AUTHOR, E9_REVIEWER, SYS_DUAL, AUTHOR_C, REVIEWER_C, AUTHOR_D, REVIEWER_D}) {
            su.update("insert into users(id,email) values (?,?)", u, u + "@x.test");
        }
        for (UUID u : new UUID[] {AUTHOR_A, AUTHOR_A_BOTH, REVIEWER_A, REVIEWER_A2, REVIEWER_A_TOEIC, DUAL_A, LEARNER_A, E9_REVIEWER}) {
            su.update("insert into org_memberships(org_id,user_id) values (?,?)", ORG_A, u);
        }
        for (UUID u : new UUID[] {AUTHOR_B, REVIEWER_B}) {
            su.update("insert into org_memberships(org_id,user_id) values (?,?)", ORG_B, u);
        }
        for (UUID u : new UUID[] {AUTHOR_C, REVIEWER_C}) {
            su.update("insert into org_memberships(org_id,user_id) values (?,?)", ORG_C, u);
        }
        for (UUID u : new UUID[] {AUTHOR_D, REVIEWER_D}) {
            su.update("insert into org_memberships(org_id,user_id) values (?,?)", ORG_D, u);
        }
        grant(AUTHOR_D, ORG_D, "IELTS", "AUTHOR");
        grant(REVIEWER_D, ORG_D, "IELTS", "REVIEWER");
        grant(AUTHOR_C, ORG_C, "IELTS", "AUTHOR");
        grant(REVIEWER_C, ORG_C, "IELTS", "REVIEWER");
        grant(AUTHOR_A, ORG_A, "IELTS", "AUTHOR");
        grant(AUTHOR_A_BOTH, ORG_A, "IELTS", "AUTHOR");
        grant(AUTHOR_A_BOTH, ORG_A, "TOEIC", "AUTHOR");
        grant(REVIEWER_A, ORG_A, "IELTS", "REVIEWER");
        grant(REVIEWER_A2, ORG_A, "IELTS", "REVIEWER");
        grant(REVIEWER_A_TOEIC, ORG_A, "TOEIC", "REVIEWER");
        grant(DUAL_A, ORG_A, "IELTS", "AUTHOR");
        grant(DUAL_A, ORG_A, "IELTS", "REVIEWER");
        grant(LEARNER_A, ORG_A, null, "LEARNER");
        grant(AUTHOR_B, ORG_B, "IELTS", "AUTHOR");
        grant(REVIEWER_B, ORG_B, "IELTS", "REVIEWER");
        grant(E9_AUTHOR, null, "IELTS", "AUTHOR");
        grant(E9_REVIEWER, null, "IELTS", "REVIEWER");
        grant(SYS_DUAL, null, null, "SYS_ADMIN");
        grant(SYS_DUAL, null, "IELTS", "AUTHOR");
        grant(SYS_DUAL, null, "IELTS", "REVIEWER");
        su.execute("create table test_items (id uuid primary key, owner_org_id uuid, exam_code text not null, author_id uuid not null,"
                + " status text not null default 'DRAFT', body text not null default 'v1')");
        su.execute("create table test_item_deps (item_id uuid not null, dep_id uuid not null)");
        // attached exactly as P2 attaches it to its *_versions tables
        su.execute("create trigger test_items_lifecycle before insert or update or delete on test_items for each row execute function e9x_guard_version_lifecycle()");
        // baseline-shaped policies (see `questions` in V202611060001): English9x rows are readable by everyone when PUBLISHED, by staff
        // always; an organisation reads and writes its own rows only. An organisation's reviewer therefore has no UPDATE policy on
        // English9x rows, which is what FOR SHARE needs.
        su.execute("alter table test_items enable row level security");
        su.execute("create policy ti_select on test_items for select using ((owner_org_id is null and (status = 'PUBLISHED' or e9x_is_staff()))"
                + " or (owner_org_id is not null and owner_org_id = e9x_current_org()))");
        su.execute("create policy ti_insert on test_items for insert with check ((owner_org_id is null and e9x_is_staff()) or (owner_org_id is not null and owner_org_id = e9x_current_org()))");
        su.execute("create policy ti_update on test_items for update using ((owner_org_id is null and e9x_is_staff()) or (owner_org_id is not null and owner_org_id = e9x_current_org()))"
                + " with check ((owner_org_id is null and e9x_is_staff()) or (owner_org_id is not null and owner_org_id = e9x_current_org()))");
        su.execute("create policy ti_delete on test_items for delete using ((owner_org_id is null and e9x_is_staff()) or (owner_org_id is not null and owner_org_id = e9x_current_org()))");
        su.execute("grant select, insert, update, delete on test_items, test_item_deps to e9x_app");
    }

    static void grant(UUID user, UUID org, String exam, String role) {
        su.update("insert into role_grants(user_id,org_id,exam_code,role) values (?,?,?,?)", user, org, exam, role);
    }

    @BeforeEach
    void reset() {
        recorder.committed().clear();
        recorder.workflowEvents().clear();
        recorder.publishedInsideTransaction().clear();
        items.afterDependencyLocked = id -> {};
    }

    // ---- helpers ----------------------------------------------------------------------------------------------------------

    static UUID item(UUID owner, String exam, UUID author, String status, UUID... deps) {
        UUID id = UUID.randomUUID();
        su.update("insert into test_items(id,owner_org_id,exam_code,author_id,status) values (?,?,?,?,?)", id, owner, exam, author, status);
        for (UUID d : deps) {
            su.update("insert into test_item_deps(item_id,dep_id) values (?,?)", id, d);
        }
        return id;
    }

    static String status(UUID item) {
        return su.queryForObject("select status from test_items where id = ?", String.class, item);
    }

    static String reviewStatus(UUID review) {
        return su.queryForObject("select status from review_requests where id = ?", String.class, review);
    }

    static <T> T as(UUID user, UUID org, Supplier<T> body) {
        try (var scope = CurrentContextHolder.bind(user, org)) {
            return body.get();
        }
    }

    static void asDo(UUID user, UUID org, Runnable body) {
        as(user, org, () -> {
            body.run();
            return null;
        });
    }

    /** The whole open queue, page by page. */
    List<ReviewQueueEntry> queue(UUID user, UUID org) {
        List<ReviewQueueEntry> all = new ArrayList<>();
        for (int offset = 0;; offset += 50) {
            int at = offset;
            List<ReviewQueueEntry> page = as(user, org, () -> reviews.open(at, 50));
            all.addAll(page);
            if (page.size() < 50) {
                return all;
            }
        }
    }

    int bundlesVisibleTo(UUID user, UUID org, UUID bundle) {
        Integer n = as(user, org, () -> new TransactionTemplate(tm).execute(st ->
                appJdbc.queryForObject("select count(*) from review_bundles where id = ?", Integer.class, bundle)));
        return n;
    }

    static VersionRef ref(UUID id) {
        return new VersionRef("item", id);
    }

    UUID submitted(UUID author, UUID org, UUID item) {
        return as(author, org, () -> reviews.submit(ref(item)));
    }

    static List<UUID> reviewIdsOfBundle(UUID bundle) {
        return su.queryForList("select id from review_requests where bundle_id = ? order by id", UUID.class, bundle);
    }

    // ---- the required behaviours -----------------------------------------------------------------------------------------------

    @Test
    void authorCannotApproveOwnVersion() {
        // a person who holds AUTHOR and REVIEWER, and wrote and submitted the version
        UUID mine = item(ORG_A, "IELTS", DUAL_A, "DRAFT");
        UUID review = submitted(DUAL_A, ORG_A, mine);
        assertThrows(SelfReviewException.class, () -> asDo(DUAL_A, ORG_A, () -> reviews.approve(review)));
        assertThrows(SelfReviewException.class, () -> asDo(DUAL_A, ORG_A, () -> reviews.requestChanges(review, "fine")),
                "nor may they send it back to themselves");
        assertEquals("IN_REVIEW", status(mine));
        assertEquals("IN_REVIEW", reviewStatus(review));
        assertEquals(List.of(), recorder.committed());

        // wrote by one person, submitted by another: neither of them may approve
        UUID theirs = item(ORG_A, "IELTS", DUAL_A, "DRAFT");
        UUID review2 = submitted(AUTHOR_A, ORG_A, theirs);
        assertThrows(SelfReviewException.class, () -> asDo(DUAL_A, ORG_A, () -> reviews.approve(review2)), "the author");
        assertEquals(DUAL_A, as(REVIEWER_A, ORG_A, () -> reviews.find(review2)).orElseThrow().authorId(),
                "the author is the creator of the version, not whoever submitted it");
        assertEquals(AUTHOR_A, as(REVIEWER_A, ORG_A, () -> reviews.find(review2)).orElseThrow().submittedBy());
        assertEquals("IN_REVIEW", status(theirs));

        // a SYS_ADMIN who also reviews and wrote it is refused too; English9x context
        UUID sys = item(null, "IELTS", SYS_DUAL, "DRAFT");
        UUID review3 = submitted(SYS_DUAL, null, sys);
        assertThrows(SelfReviewException.class, () -> asDo(SYS_DUAL, null, () -> reviews.approve(review3)));
        assertEquals("IN_REVIEW", status(sys));
        assertEquals(List.of(), recorder.committed());

        // an independent reviewer approves it
        asDo(E9_REVIEWER, null, () -> reviews.approve(review3));
        assertEquals("PUBLISHED", status(sys));
    }

    @Test
    void changesRequestedReturnsToDraftWithComment() {
        UUID it = item(ORG_A, "IELTS", AUTHOR_A, "DRAFT");
        UUID review = submitted(AUTHOR_A, ORG_A, it);
        assertEquals("IN_REVIEW", status(it));

        assertThrows(InvalidReviewInputException.class, () -> asDo(REVIEWER_A, ORG_A, () -> reviews.requestChanges(review, "   ")), "a comment is required");
        assertThrows(InvalidReviewInputException.class, () -> asDo(REVIEWER_A, ORG_A, () -> reviews.requestChanges(review, null)));
        assertEquals("IN_REVIEW", status(it));

        asDo(REVIEWER_A, ORG_A, () -> reviews.requestChanges(review, "Option C is also correct"));
        assertEquals("CHANGES_REQUESTED", status(it), "back with the author, editable again");
        ReviewView v = as(AUTHOR_A, ORG_A, () -> reviews.find(review)).orElseThrow();
        assertEquals(ContentStatus.CHANGES_REQUESTED, v.status());
        assertEquals(Optional.of("Option C is also correct"), v.comment());
        assertEquals(Optional.of(REVIEWER_A), v.reviewerId());
        assertTrue(v.decidedAt().isPresent());
        assertEquals(List.of(), recorder.committed(), "nothing was published");

        // the author edits (allowed in this state), submits again: a new review, then approval publishes it once
        su.update("update test_items set body = 'v2' where id = ?", it);
        UUID second = submitted(AUTHOR_A, ORG_A, it);
        assertFalse(second.equals(review));
        assertEquals("IN_REVIEW", status(it));
        asDo(REVIEWER_A, ORG_A, () -> reviews.approve(second));
        assertEquals("PUBLISHED", status(it));
        assertEquals(List.of(new ContentPublished("item", it, Optional.of(ORG_A), "IELTS")), recorder.committed());
        assertEquals(List.of(true), recorder.publishedInsideTransaction(), "published inside the transaction of the approval");
        // the earlier review keeps its history
        assertEquals("CHANGES_REQUESTED", reviewStatus(review));

        // a decided review is final
        assertThrows(ReviewConflictException.class, () -> asDo(REVIEWER_A, ORG_A, () -> reviews.approve(review)));
        assertThrows(ReviewConflictException.class, () -> asDo(REVIEWER_A, ORG_A, () -> reviews.approve(second)));
        assertEquals(1, recorder.committed().size());
    }

    @Test
    void approveBlockedByUnpublishedDependency() {
        UUID draftDep = item(ORG_A, "IELTS", AUTHOR_A, "DRAFT");
        UUID reviewDep = item(ORG_A, "IELTS", AUTHOR_A, "IN_REVIEW");
        UUID okDep = item(ORG_A, "IELTS", AUTHOR_A, "PUBLISHED");
        UUID missingDep = UUID.randomUUID();
        UUID test = item(ORG_A, "IELTS", AUTHOR_A, "DRAFT", draftDep, reviewDep, okDep, missingDep);
        UUID review = submitted(AUTHOR_A, ORG_A, test);

        UnpublishedDependencyException e = assertThrows(UnpublishedDependencyException.class,
                () -> asDo(REVIEWER_A, ORG_A, () -> reviews.approve(review)));
        assertEquals(Set.of(ref(draftDep), ref(reviewDep), ref(missingDep)), Set.copyOf(e.missing()), "every unpublished dependency is listed, the published one is not");
        assertEquals("IN_REVIEW", status(test));
        assertEquals("IN_REVIEW", reviewStatus(review));
        assertEquals(List.of(), recorder.committed());

        su.update("delete from test_item_deps where item_id = ?", test);
        su.update("insert into test_item_deps(item_id,dep_id) values (?,?)", test, okDep);
        asDo(REVIEWER_A, ORG_A, () -> reviews.approve(review));
        assertEquals("PUBLISHED", status(test));
    }

    @Test
    void bundleApprovesAllOrNothing() {
        // a chain inside the bundle: course -> lesson -> question, listed in the worst order
        UUID q = item(ORG_A, "IELTS", AUTHOR_A, "DRAFT");
        UUID lesson = item(ORG_A, "IELTS", AUTHOR_A, "DRAFT", q);
        UUID course = item(ORG_A, "IELTS", AUTHOR_A, "DRAFT", lesson);
        UUID bundle = as(AUTHOR_A, ORG_A, () -> reviews.submit(new BundleRef(List.of(ref(course), ref(lesson), ref(q)))));
        assertEquals(3, reviewIdsOfBundle(bundle).size());
        assertEquals(Set.of("IN_REVIEW"), Set.copyOf(List.of(status(q), status(lesson), status(course))));
        asDo(REVIEWER_A, ORG_A, () -> reviews.approveBundle(bundle));
        assertEquals(List.of("PUBLISHED", "PUBLISHED", "PUBLISHED"), List.of(status(q), status(lesson), status(course)));
        assertEquals(3, recorder.committed().size());
        assertEquals(Set.of(q, lesson, course), Set.copyOf(recorder.committed().stream().map(ContentPublished::versionId).toList()));
        assertThrows(ReviewConflictException.class, () -> asDo(REVIEWER_A, ORG_A, () -> reviews.approveBundle(bundle)), "once");
        assertEquals(3, recorder.committed().size());

        // one member depends on something unpublished outside the bundle: nothing is published, no event
        recorder.committed().clear();
        UUID outside = item(ORG_A, "IELTS", AUTHOR_A, "DRAFT");
        UUID m1 = item(ORG_A, "IELTS", AUTHOR_A, "DRAFT");
        UUID m2 = item(ORG_A, "IELTS", AUTHOR_A, "DRAFT");
        UUID m3 = item(ORG_A, "IELTS", AUTHOR_A, "DRAFT", outside);
        UUID b2 = as(AUTHOR_A, ORG_A, () -> reviews.submit(new BundleRef(List.of(ref(m1), ref(m2), ref(m3)))));
        UnpublishedDependencyException e = assertThrows(UnpublishedDependencyException.class, () -> asDo(REVIEWER_A, ORG_A, () -> reviews.approveBundle(b2)));
        assertEquals(List.of(ref(outside)), e.missing());
        assertEquals(List.of("IN_REVIEW", "IN_REVIEW", "IN_REVIEW"), List.of(status(m1), status(m2), status(m3)));
        assertEquals(Set.of("IN_REVIEW"), Set.copyOf(reviewIdsOfBundle(b2).stream().map(ReviewServiceIT::reviewStatus).toList()));
        assertEquals(List.of(), recorder.committed());

        // one member was written by the reviewer: none is published
        UUID n1 = item(ORG_A, "IELTS", AUTHOR_A, "DRAFT");
        UUID n2 = item(ORG_A, "IELTS", DUAL_A, "DRAFT");
        UUID b3 = as(AUTHOR_A, ORG_A, () -> reviews.submit(new BundleRef(List.of(ref(n1), ref(n2)))));
        assertThrows(SelfReviewException.class, () -> asDo(DUAL_A, ORG_A, () -> reviews.approveBundle(b3)));
        assertEquals(List.of("IN_REVIEW", "IN_REVIEW"), List.of(status(n1), status(n2)));
        assertEquals(List.of(), recorder.committed());

        // a member of an exam the reviewer does not review: refused as a whole (they cannot even see that member)
        UUID i1 = item(ORG_A, "IELTS", AUTHOR_A_BOTH, "DRAFT");
        UUID t1 = item(ORG_A, "TOEIC", AUTHOR_A_BOTH, "DRAFT");
        UUID b4 = as(AUTHOR_A_BOTH, ORG_A, () -> reviews.submit(new BundleRef(List.of(ref(i1), ref(t1)))));
        assertThrows(AccessDeniedException.class, () -> asDo(REVIEWER_A, ORG_A, () -> reviews.approveBundle(b4)));
        assertThrows(AccessDeniedException.class, () -> asDo(REVIEWER_A_TOEIC, ORG_A, () -> reviews.approveBundle(b4)));
        assertEquals(List.of("IN_REVIEW", "IN_REVIEW"), List.of(status(i1), status(t1)));
        assertEquals(List.of(), recorder.committed());
        // ... and the whole bundle can be sent back, or decided by someone who may decide all of it
        assertThrows(AccessDeniedException.class, () -> asDo(REVIEWER_A, ORG_A, () -> reviews.requestBundleChanges(b4, "no")));
        assertEquals(List.of("IN_REVIEW", "IN_REVIEW"), List.of(status(i1), status(t1)));

        // requesting changes for a bundle returns every member
        UUID r1 = item(ORG_A, "IELTS", AUTHOR_A, "DRAFT");
        UUID r2 = item(ORG_A, "IELTS", AUTHOR_A, "DRAFT");
        UUID b5 = as(AUTHOR_A, ORG_A, () -> reviews.submit(new BundleRef(List.of(ref(r1), ref(r2)))));
        asDo(REVIEWER_A, ORG_A, () -> reviews.requestBundleChanges(b5, "redo both"));
        assertEquals(List.of("CHANGES_REQUESTED", "CHANGES_REQUESTED"), List.of(status(r1), status(r2)));
        assertEquals(2, as(AUTHOR_A, ORG_A, () -> reviews.members(b5, 0, 100)).stream().filter(v -> v.comment().equals(Optional.of("redo both"))).count());
    }

    @Test
    void bundleSubmissionIsAllOrNothingToo() {
        int bundlesBefore = su.queryForObject("select count(*) from review_bundles", Integer.class);
        UUID ok = item(ORG_A, "IELTS", AUTHOR_A, "DRAFT");
        UUID busy = item(ORG_A, "IELTS", AUTHOR_A, "IN_REVIEW");
        assertThrows(ReviewConflictException.class, () -> asDo(AUTHOR_A, ORG_A, () -> reviews.submit(new BundleRef(List.of(ref(ok), ref(busy))))));
        assertEquals("DRAFT", status(ok));
        assertEquals(0, su.queryForObject("select count(*) from review_requests where version_id = ?", Integer.class, ok));
        // a member of another organisation
        UUID foreign = item(ORG_B, "IELTS", AUTHOR_B, "DRAFT");
        assertThrows(ReviewNotFoundException.class, () -> asDo(AUTHOR_A, ORG_A, () -> reviews.submit(new BundleRef(List.of(ref(ok), ref(foreign))))));
        assertEquals("DRAFT", status(ok));
        assertEquals("DRAFT", status(foreign));
        // a member of an exam the author may not submit
        UUID toeic = item(ORG_A, "TOEIC", AUTHOR_A, "DRAFT");
        assertThrows(AccessDeniedException.class, () -> asDo(AUTHOR_A, ORG_A, () -> reviews.submit(new BundleRef(List.of(ref(ok), ref(toeic))))));
        assertEquals("DRAFT", status(ok));
        // the same version twice
        assertThrows(InvalidReviewInputException.class, () -> asDo(AUTHOR_A, ORG_A, () -> reviews.submit(new BundleRef(List.of(ref(ok), ref(ok))))));
        assertEquals(bundlesBefore, su.queryForObject("select count(*) from review_bundles", Integer.class), "no bundle row survived a refused submission");
        assertEquals("DRAFT", status(ok));
    }

    @Test
    void aBundleMemberIsDecidedWithItsBundle() {
        UUID a = item(ORG_A, "IELTS", AUTHOR_A, "DRAFT");
        UUID b = item(ORG_A, "IELTS", AUTHOR_A, "DRAFT");
        UUID bundle = as(AUTHOR_A, ORG_A, () -> reviews.submit(new BundleRef(List.of(ref(a), ref(b)))));
        UUID member = reviewIdsOfBundle(bundle).get(0);
        assertThrows(ReviewConflictException.class, () -> asDo(REVIEWER_A, ORG_A, () -> reviews.approve(member)));
        assertThrows(ReviewConflictException.class, () -> asDo(REVIEWER_A, ORG_A, () -> reviews.requestChanges(member, "x")));
        assertEquals("IN_REVIEW", status(a));
    }

    @Test
    void publishedVersionIsImmutable() {
        UUID it = item(ORG_A, "IELTS", AUTHOR_A, "DRAFT");
        UUID review = submitted(AUTHOR_A, ORG_A, it);
        // while under review it is frozen
        assertThrows(DataIntegrityViolationException.class, () -> su.update("update test_items set body = 'sneaky' where id = ?", it));
        asDo(REVIEWER_A, ORG_A, () -> reviews.approve(review));
        assertEquals("PUBLISHED", status(it));

        assertThrows(DataIntegrityViolationException.class, () -> as(AUTHOR_A, ORG_A, () -> new TransactionTemplate(tm).execute(st ->
                appJdbc.update("update test_items set body = 'changed' where id = ?", it))), "the application role");
        assertThrows(DataIntegrityViolationException.class, () -> su.update("update test_items set body = 'changed' where id = ?", it), "even the superuser");
        assertThrows(DataIntegrityViolationException.class, () -> su.update("update test_items set status = 'DRAFT' where id = ?", it));
        assertThrows(DataIntegrityViolationException.class, () -> su.update("delete from test_items where id = ?", it));
        assertEquals("v1", su.queryForObject("select body from test_items where id = ?", String.class, it));
        // the only way out is ARCHIVED
        su.update("update test_items set status = 'ARCHIVED' where id = ?", it);
        assertThrows(DataIntegrityViolationException.class, () -> su.update("update test_items set status = 'PUBLISHED' where id = ?", it));
    }

    // ---- tenant boundary, through the real binding ----------------------------------------------------------------------------------

    @Test
    void aReviewerOfAnotherOrganisationCannotSeeOrDecideAReview() {
        UUID it = item(ORG_A, "IELTS", AUTHOR_A, "DRAFT");
        UUID review = submitted(AUTHOR_A, ORG_A, it);
        for (UUID org : new UUID[] {ORG_B, null}) {
            assertEquals(Optional.empty(), as(REVIEWER_B, org, () -> reviews.find(review)), "context " + org);
            assertTrue(queue(REVIEWER_B, org).stream().noneMatch(v -> v.ownerOrgId().equals(Optional.of(ORG_A))),
                    "B's queue holds no review of A");
            assertThrows(ReviewNotFoundException.class, () -> asDo(REVIEWER_B, org, () -> reviews.approve(review)));
            assertThrows(ReviewNotFoundException.class, () -> asDo(REVIEWER_B, org, () -> reviews.requestChanges(review, "no")));
        }
        assertThrows(ReviewNotFoundException.class, () -> asDo(AUTHOR_B, ORG_B, () -> reviews.approve(review)));
        assertEquals("IN_REVIEW", status(it));
        assertEquals("IN_REVIEW", reviewStatus(review));
        assertEquals(List.of(), recorder.committed());
        // the same call from inside the right organisation works (the positive control)
        assertEquals(1, queue(REVIEWER_A, ORG_A).stream().filter(v -> v.reviewId().equals(Optional.of(review))).count());
    }

    @Test
    void anOrganisationReviewerCannotTouchEnglish9xContentAndViceVersa() {
        UUID e9 = item(null, "IELTS", E9_AUTHOR, "DRAFT");
        UUID e9Review = submitted(E9_AUTHOR, null, e9);
        UUID a = item(ORG_A, "IELTS", AUTHOR_A, "DRAFT");
        UUID aReview = submitted(AUTHOR_A, ORG_A, a);

        // reviewer of A, in A or in the personal context, against the English9x review
        for (UUID org : new UUID[] {ORG_A, null}) {
            assertEquals(Optional.empty(), as(REVIEWER_A, org, () -> reviews.find(e9Review)));
            assertThrows(ReviewNotFoundException.class, () -> asDo(REVIEWER_A, org, () -> reviews.approve(e9Review)));
            assertThrows(ReviewNotFoundException.class, () -> asDo(REVIEWER_A, org, () -> reviews.requestChanges(e9Review, "x")));
        }
        // the English9x reviewer, who is also a member of A, against A's review: in A's context an English9x grant counts for nothing,
        // in the personal context the review is not English9x's
        for (UUID org : new UUID[] {ORG_A, null}) {
            assertEquals(Optional.empty(), as(E9_REVIEWER, org, () -> reviews.find(aReview)), "context " + org);
            assertThrows(ReviewNotFoundException.class, () -> asDo(E9_REVIEWER, org, () -> reviews.approve(aReview)));
            assertThrows(ReviewNotFoundException.class, () -> asDo(E9_REVIEWER, org, () -> reviews.requestChanges(aReview, "x")));
        }
        assertEquals("IN_REVIEW", status(e9));
        assertEquals("IN_REVIEW", status(a));
        assertEquals(List.of(), recorder.committed());
        // the open queues are separate
        List<ReviewQueueEntry> queue = queue(E9_REVIEWER, null);
        assertTrue(queue.stream().anyMatch(v -> v.reviewId().equals(Optional.of(e9Review))));
        assertTrue(queue.stream().allMatch(v -> v.ownerOrgId().isEmpty()), "the English9x queue holds English9x reviews only");
        assertTrue(queue(REVIEWER_A, ORG_A).stream().noneMatch(v -> v.reviewId().equals(Optional.of(e9Review))));
        // English9x publication carries no owner
        asDo(E9_REVIEWER, null, () -> reviews.approve(e9Review));
        assertEquals(List.of(new ContentPublished("item", e9, Optional.empty(), "IELTS")), recorder.committed());
    }

    @Test
    void anExamScopedReviewerOnlyActsOnThatExam() {
        UUID ielts = item(ORG_A, "IELTS", AUTHOR_A, "DRAFT");
        UUID review = submitted(AUTHOR_A, ORG_A, ielts);
        assertThrows(ReviewNotFoundException.class, () -> asDo(REVIEWER_A_TOEIC, ORG_A, () -> reviews.approve(review)));
        assertThrows(ReviewNotFoundException.class, () -> asDo(REVIEWER_A_TOEIC, ORG_A, () -> reviews.requestChanges(review, "x")));
        assertTrue(queue(REVIEWER_A_TOEIC, ORG_A).stream().allMatch(v -> v.examCode().equals("TOEIC")),
                "the TOEIC reviewer's queue holds TOEIC reviews only");
        // those who see the review but do not hold REVIEWER of its exam: refused, not "not found"
        assertThrows(AccessDeniedException.class, () -> asDo(AUTHOR_A, ORG_A, () -> reviews.approve(review)));
        assertEquals("IN_REVIEW", status(ielts));
        // a learner sees nothing
        assertEquals(Optional.empty(), as(LEARNER_A, ORG_A, () -> reviews.find(review)));
        assertThrows(ReviewNotFoundException.class, () -> asDo(LEARNER_A, ORG_A, () -> reviews.approve(review)));
        // no user at all
        assertThrows(AccessDeniedException.class, () -> reviews.approve(review));
    }

    @Test
    void submitNeedsAnAuthorOfThatExamInThatContext() {
        UUID a = item(ORG_A, "IELTS", AUTHOR_A, "DRAFT");
        UUID aToeic = item(ORG_A, "TOEIC", AUTHOR_A, "DRAFT");
        UUID b = item(ORG_B, "IELTS", AUTHOR_B, "DRAFT");
        UUID e9 = item(null, "IELTS", E9_AUTHOR, "DRAFT");
        assertThrows(AccessDeniedException.class, () -> asDo(AUTHOR_A, ORG_A, () -> reviews.submit(ref(aToeic))), "AUTHOR of IELTS only");
        assertThrows(AccessDeniedException.class, () -> asDo(REVIEWER_A, ORG_A, () -> reviews.submit(ref(a))), "a reviewer is not an author");
        assertThrows(AccessDeniedException.class, () -> asDo(LEARNER_A, ORG_A, () -> reviews.submit(ref(a))));
        assertThrows(ReviewNotFoundException.class, () -> asDo(AUTHOR_B, ORG_B, () -> reviews.submit(ref(a))), "another organisation's version");
        assertThrows(ReviewNotFoundException.class, () -> asDo(AUTHOR_A, null, () -> reviews.submit(ref(a))), "wrong context");
        assertThrows(ReviewNotFoundException.class, () -> asDo(AUTHOR_A, ORG_A, () -> reviews.submit(ref(b))));
        assertThrows(ReviewNotFoundException.class, () -> asDo(AUTHOR_A, ORG_A, () -> reviews.submit(ref(e9))), "English9x version from an organisation");
        assertThrows(ReviewNotFoundException.class, () -> asDo(E9_AUTHOR, null, () -> reviews.submit(ref(a))));
        assertThrows(ReviewNotFoundException.class, () -> asDo(AUTHOR_A, ORG_A, () -> reviews.submit(ref(UUID.randomUUID()))));
        assertThrows(InvalidReviewInputException.class, () -> asDo(AUTHOR_A, ORG_A, () -> reviews.submit(new VersionRef("nonsense", a))));
        assertEquals(List.of("DRAFT", "DRAFT", "DRAFT", "DRAFT"), List.of(status(a), status(aToeic), status(b), status(e9)));
        assertEquals(0, su.queryForObject("select count(*) from review_requests where version_id in (?,?,?,?)", Integer.class, a, aToeic, b, e9));
        // a version that is already in review or published cannot be submitted again
        UUID busy = item(ORG_A, "IELTS", AUTHOR_A, "IN_REVIEW");
        UUID live = item(ORG_A, "IELTS", AUTHOR_A, "PUBLISHED");
        assertThrows(ReviewConflictException.class, () -> asDo(AUTHOR_A, ORG_A, () -> reviews.submit(ref(busy))));
        assertThrows(ReviewConflictException.class, () -> asDo(AUTHOR_A, ORG_A, () -> reviews.submit(ref(live))));
    }

    // ---- concurrency ------------------------------------------------------------------------------------------------------------------

    private static <T> List<Object> race(Callable<T> first, Callable<T> second) throws Exception {
        ExecutorService pool = Executors.newFixedThreadPool(2);
        try {
            CyclicBarrier go = new CyclicBarrier(2);
            List<Future<Object>> futures = new ArrayList<>();
            for (Callable<T> c : List.of(first, second)) {
                futures.add(pool.submit(() -> {
                    go.await(10, TimeUnit.SECONDS);
                    try {
                        return c.call();
                    } catch (Throwable t) {
                        return t;
                    }
                }));
            }
            List<Object> results = new ArrayList<>();
            for (Future<Object> f : futures) {
                results.add(f.get(60, TimeUnit.SECONDS));
            }
            return results;
        } finally {
            pool.shutdownNow();
        }
    }

    @Test
    void twoSimultaneousApprovalsHaveExactlyOneWinner() throws Exception {
        for (int round = 0; round < 15; round++) {
            UUID it = item(ORG_A, "IELTS", AUTHOR_A, "DRAFT");
            UUID review = submitted(AUTHOR_A, ORG_A, it);
            List<Object> r = race(
                    () -> as(REVIEWER_A, ORG_A, () -> { reviews.approve(review); return "ok"; }),
                    () -> as(REVIEWER_A2, ORG_A, () -> { reviews.approve(review); return "ok"; }));
            assertEquals(1, r.stream().filter("ok"::equals).count(), "round " + round + ": " + r);
            assertEquals(1, r.stream().filter(x -> x instanceof ReviewConflictException).count(), "round " + round + ": " + r);
            assertEquals("PUBLISHED", status(it));
            assertEquals(1, recorder.forVersion(it).size(), "one event");
        }
    }

    @Test
    void approveAndRequestChangesRaceHasOneWinnerAndAConsistentEnd() throws Exception {
        for (int round = 0; round < 20; round++) {
            UUID it = item(ORG_A, "IELTS", AUTHOR_A, "DRAFT");
            UUID review = submitted(AUTHOR_A, ORG_A, it);
            List<Object> r = race(
                    () -> as(REVIEWER_A, ORG_A, () -> { reviews.approve(review); return "approved"; }),
                    () -> as(REVIEWER_A2, ORG_A, () -> { reviews.requestChanges(review, "no"); return "changes"; }));
            long wins = r.stream().filter(x -> "approved".equals(x) || "changes".equals(x)).count();
            assertEquals(1, wins, "round " + round + ": " + r);
            assertEquals(1, r.stream().filter(x -> x instanceof ReviewConflictException).count(), "round " + round + ": " + r);
            boolean approved = r.contains("approved");
            assertEquals(approved ? "PUBLISHED" : "CHANGES_REQUESTED", status(it));
            assertEquals(approved ? "PUBLISHED" : "CHANGES_REQUESTED", reviewStatus(review));
            assertEquals(approved ? 1 : 0, recorder.forVersion(it).size());
        }
    }

    @Test
    void twoSimultaneousBundleApprovalsHaveExactlyOneWinner() throws Exception {
        for (int round = 0; round < 8; round++) {
            UUID x = item(ORG_A, "IELTS", AUTHOR_A, "DRAFT");
            UUID y = item(ORG_A, "IELTS", AUTHOR_A, "DRAFT");
            UUID bundle = as(AUTHOR_A, ORG_A, () -> reviews.submit(new BundleRef(List.of(ref(x), ref(y)))));
            List<Object> r = race(
                    () -> as(REVIEWER_A, ORG_A, () -> { reviews.approveBundle(bundle); return "ok"; }),
                    () -> as(REVIEWER_A2, ORG_A, () -> { reviews.approveBundle(bundle); return "ok"; }));
            assertEquals(1, r.stream().filter("ok"::equals).count(), "round " + round + ": " + r);
            assertEquals(1, r.stream().filter(o -> o instanceof ReviewConflictException).count(), "round " + round + ": " + r);
            assertEquals(1, recorder.forVersion(x).size());
            assertEquals(1, recorder.forVersion(y).size());
        }
    }

    @Test
    void theDependencyCheckHoldsAgainstAConcurrentChange() throws Exception {
        UUID dep = item(ORG_A, "IELTS", AUTHOR_A, "PUBLISHED");
        UUID test = item(ORG_A, "IELTS", AUTHOR_A, "DRAFT", dep);
        UUID review = submitted(AUTHOR_A, ORG_A, test);

        CountDownLatch checked = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        items.afterDependencyLocked = id -> {
            if (id.equals(dep)) {
                checked.countDown();
                try {
                    assertTrue(release.await(30, TimeUnit.SECONDS));
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                }
            }
        };
        ExecutorService pool = Executors.newFixedThreadPool(2);
        try {
            Future<?> approval = pool.submit(() -> asDo(REVIEWER_A, ORG_A, () -> reviews.approve(review)));
            assertTrue(checked.await(30, TimeUnit.SECONDS), "the approval has read and locked its dependency");
            // somebody archives the dependency right now: it must wait for the approval, not slip in between check and publish
            Future<?> archive = pool.submit(() -> su.update("update test_items set status = 'ARCHIVED' where id = ?", dep));
            Thread.sleep(1000);
            assertFalse(archive.isDone(), "the dependency is locked while the approval runs");
            assertEquals("PUBLISHED", status(dep));
            release.countDown();
            approval.get(30, TimeUnit.SECONDS);
            archive.get(30, TimeUnit.SECONDS);
        } finally {
            release.countDown();
            pool.shutdownNow();
        }
        assertEquals("PUBLISHED", status(test));
        assertEquals("ARCHIVED", status(dep), "the change came after the approval, in order");
        assertEquals(1, recorder.forVersion(test).size());
    }

    @Test
    void sequentialCallsOnOneThreadWithDifferentUsersDoNotShareState() {
        // each call sees only its own context, even when the pooled connection is the same
        UUID a = item(ORG_A, "IELTS", AUTHOR_A, "DRAFT");
        UUID review = submitted(AUTHOR_A, ORG_A, a);
        assertNotNull(as(REVIEWER_A, ORG_A, () -> reviews.find(review)).orElse(null));
        assertEquals(Optional.empty(), as(REVIEWER_B, ORG_B, () -> reviews.find(review)));
        assertNotNull(as(REVIEWER_A, ORG_A, () -> reviews.find(review)).orElse(null));
        assertInstanceOf(ReviewNotFoundException.class, assertThrows(RuntimeException.class, () -> asDo(REVIEWER_B, ORG_B, () -> reviews.approve(review))));
        assertEquals("IN_REVIEW", status(a));
    }

    // ---- fix round 1 ----------------------------------------------------------------------------------------------------------------------

    @Test
    void anOrganisationDependsOnPublishedEnglish9xContentButNotOnADraftOrAnotherOrganisationsContent() {
        UUID e9Published = item(null, "IELTS", E9_AUTHOR, "PUBLISHED");
        UUID e9Draft = item(null, "IELTS", E9_AUTHOR, "DRAFT");
        UUID bPublished = item(ORG_B, "IELTS", AUTHOR_B, "PUBLISHED");
        UUID own = item(ORG_A, "IELTS", AUTHOR_A, "PUBLISHED");

        UUID ok = item(ORG_A, "IELTS", AUTHOR_A, "DRAFT", e9Published, own);
        UUID okReview = submitted(AUTHOR_A, ORG_A, ok);
        asDo(REVIEWER_A, ORG_A, () -> reviews.approve(okReview));
        assertEquals("PUBLISHED", status(ok), "an English9x PUBLISHED dependency satisfies a centre's version");

        UUID draftDep = item(ORG_A, "IELTS", AUTHOR_A, "DRAFT", e9Draft);
        UUID r1 = submitted(AUTHOR_A, ORG_A, draftDep);
        UnpublishedDependencyException e1 = assertThrows(UnpublishedDependencyException.class, () -> asDo(REVIEWER_A, ORG_A, () -> reviews.approve(r1)));
        assertEquals(List.of(ref(e9Draft)), e1.missing());

        UUID foreignDep = item(ORG_A, "IELTS", AUTHOR_A, "DRAFT", bPublished);
        UUID r2 = submitted(AUTHOR_A, ORG_A, foreignDep);
        UnpublishedDependencyException e2 = assertThrows(UnpublishedDependencyException.class, () -> asDo(REVIEWER_A, ORG_A, () -> reviews.approve(r2)));
        assertEquals(List.of(ref(bPublished)), e2.missing(), "another organisation's published content is not readable, so not a dependency");
        assertEquals("IN_REVIEW", status(draftDep));
        assertEquals("IN_REVIEW", status(foreignDep));
    }

    @Test
    void theQueueHasOneLinePerBundleAndPagesRealLines() {
        List<VersionRef> members = new ArrayList<>();
        for (int i = 0; i < MAX_BUNDLE; i++) {
            members.add(ref(item(ORG_C, "IELTS", AUTHOR_C, "DRAFT")));
        }
        UUID bundle = as(AUTHOR_C, ORG_C, () -> reviews.submit(new BundleRef(members)));
        UUID later = item(ORG_C, "IELTS", AUTHOR_C, "DRAFT");
        UUID laterReview = submitted(AUTHOR_C, ORG_C, later);

        List<ReviewQueueEntry> page1 = as(REVIEWER_C, ORG_C, () -> reviews.open(0, 50));
        assertEquals(2, page1.size(), "a 130-member bundle is one line, the newer standalone review is on page one");
        assertEquals(Optional.of(bundle), page1.get(0).bundleId());
        assertEquals(MAX_BUNDLE, page1.get(0).size());
        assertEquals("IELTS", page1.get(0).examCode());
        assertEquals(Optional.of(laterReview), page1.get(1).reviewId());
        assertEquals(1, page1.get(1).size());
        assertEquals(List.of(page1.get(1)), as(REVIEWER_C, ORG_C, () -> reviews.open(1, 1)), "offset and limit page the lines");
        assertEquals(List.of(), as(REVIEWER_C, ORG_C, () -> reviews.open(2, 50)));

        // the members, paged
        assertEquals(50, as(REVIEWER_C, ORG_C, () -> reviews.members(bundle, 0, 50)).size());
        assertEquals(50, as(REVIEWER_C, ORG_C, () -> reviews.members(bundle, 50, 50)).size());
        assertEquals(MAX_BUNDLE - 100, as(REVIEWER_C, ORG_C, () -> reviews.members(bundle, 100, 50)).size());
        assertEquals(List.of(), as(REVIEWER_B, ORG_B, () -> reviews.members(bundle, 0, 50)));

        // one more than the limit is refused, the limit itself was accepted above
        List<VersionRef> tooMany = new ArrayList<>(members);
        tooMany.add(ref(item(ORG_C, "IELTS", AUTHOR_C, "DRAFT")));
        int bundles = su.queryForObject("select count(*) from review_bundles", Integer.class);
        assertThrows(InvalidReviewInputException.class, () -> asDo(AUTHOR_C, ORG_C, () -> reviews.submit(new BundleRef(tooMany))));
        assertEquals(bundles, su.queryForObject("select count(*) from review_bundles", Integer.class));
    }

    @Test
    void eachWorkflowEventFiresOnceOnItsPathAndNeverOnARefusedOne() {
        UUID it = item(ORG_A, "IELTS", AUTHOR_A, "DRAFT");
        // a refused submission: nothing
        assertThrows(AccessDeniedException.class, () -> asDo(REVIEWER_A, ORG_A, () -> reviews.submit(ref(it))));
        assertEquals(List.of(), recorder.workflowEvents());
        UUID review = submitted(AUTHOR_A, ORG_A, it);
        assertEquals(List.of(new ReviewRequested("item", it, Optional.of(ORG_A), "IELTS", review, Optional.empty())), recorder.workflowEvents());
        recorder.workflowEvents().clear();
        // refused decisions: nothing
        assertThrows(AccessDeniedException.class, () -> asDo(AUTHOR_A, ORG_A, () -> reviews.approve(review)));
        assertThrows(ReviewNotFoundException.class, () -> asDo(REVIEWER_B, ORG_B, () -> reviews.requestChanges(review, "x")));
        assertThrows(InvalidReviewInputException.class, () -> asDo(REVIEWER_A, ORG_A, () -> reviews.requestChanges(review, " ")));
        assertEquals(List.of(), recorder.workflowEvents());
        asDo(REVIEWER_A, ORG_A, () -> reviews.requestChanges(review, "again"));
        assertEquals(List.of(new ReviewChangesRequested("item", it, Optional.of(ORG_A), "IELTS", review, Optional.empty(), AUTHOR_A)), recorder.workflowEvents());
        recorder.workflowEvents().clear();
        UUID second = submitted(AUTHOR_A, ORG_A, it);
        recorder.workflowEvents().clear();
        asDo(REVIEWER_A, ORG_A, () -> reviews.approve(second));
        assertEquals(List.of(new ReviewApproved("item", it, Optional.of(ORG_A), "IELTS", second, Optional.empty(), AUTHOR_A)), recorder.workflowEvents());
        assertThrows(ReviewConflictException.class, () -> asDo(REVIEWER_A, ORG_A, () -> reviews.approve(second)));
        assertEquals(1, recorder.workflowEvents().size());
        assertEquals(1, recorder.forVersion(it).size());
    }

    @Test
    void bundleEventsAreOnePerExamForRequestsAndOnePerAuthorForDecisions() {
        UUID i1 = item(ORG_A, "IELTS", AUTHOR_A_BOTH, "DRAFT");
        UUID i2 = item(ORG_A, "IELTS", AUTHOR_A_BOTH, "DRAFT");
        UUID t1 = item(ORG_A, "TOEIC", AUTHOR_A_BOTH, "DRAFT");
        UUID bundle = as(AUTHOR_A_BOTH, ORG_A, () -> reviews.submit(new BundleRef(List.of(ref(i1), ref(i2), ref(t1)))));
        List<ReviewRequested> requested = recorder.workflowEvents().stream().map(ReviewRequested.class::cast).toList();
        assertEquals(Set.of("IELTS", "TOEIC"), Set.copyOf(requested.stream().map(ReviewRequested::examCode).toList()));
        assertEquals(2, requested.size());
        assertTrue(requested.stream().allMatch(e -> e.bundleId().equals(Optional.of(bundle))));
        recorder.workflowEvents().clear();

        UUID j1 = item(ORG_A, "IELTS", AUTHOR_A, "DRAFT");
        UUID j2 = item(ORG_A, "IELTS", AUTHOR_A_BOTH, "DRAFT");
        UUID j3 = item(ORG_A, "IELTS", AUTHOR_A, "DRAFT");
        // two authors' versions submitted by one person who may submit them: AUTHOR_A_BOTH submits all three
        UUID b2 = as(AUTHOR_A_BOTH, ORG_A, () -> reviews.submit(new BundleRef(List.of(ref(j1), ref(j2), ref(j3)))));
        recorder.workflowEvents().clear();
        asDo(REVIEWER_A, ORG_A, () -> reviews.approveBundle(b2));
        List<ReviewApproved> approved = recorder.workflowEvents().stream().map(ReviewApproved.class::cast).toList();
        assertEquals(Set.of(AUTHOR_A, AUTHOR_A_BOTH), Set.copyOf(approved.stream().map(ReviewApproved::authorId).toList()));
        assertEquals(2, approved.size(), "one per author, not per member");
        assertEquals(3, recorder.committed().size(), "ContentPublished is still one per version");

        UUID k1 = item(ORG_A, "IELTS", AUTHOR_A, "DRAFT");
        UUID k2 = item(ORG_A, "IELTS", AUTHOR_A, "DRAFT");
        UUID b3 = as(AUTHOR_A, ORG_A, () -> reviews.submit(new BundleRef(List.of(ref(k1), ref(k2)))));
        recorder.workflowEvents().clear();
        asDo(REVIEWER_A, ORG_A, () -> reviews.requestBundleChanges(b3, "redo"));
        assertEquals(1, recorder.workflowEvents().size());
        assertInstanceOf(ReviewChangesRequested.class, recorder.workflowEvents().get(0));
        // a refused bundle decision publishes nothing
        UUID m1 = item(ORG_A, "IELTS", AUTHOR_A, "DRAFT");
        UUID m2 = item(ORG_A, "IELTS", DUAL_A, "DRAFT");
        UUID b4 = as(AUTHOR_A, ORG_A, () -> reviews.submit(new BundleRef(List.of(ref(m1), ref(m2)))));
        recorder.workflowEvents().clear();
        assertThrows(SelfReviewException.class, () -> asDo(DUAL_A, ORG_A, () -> reviews.approveBundle(b4)));
        assertEquals(List.of(), recorder.workflowEvents());
    }

    @Test
    void aBundleRefusalListsTheDependenciesOfEveryMemberAtOnce() {
        UUID d1 = item(ORG_A, "IELTS", AUTHOR_A, "DRAFT");
        UUID d2 = item(ORG_A, "IELTS", AUTHOR_A, "DRAFT");
        UUID m1 = item(ORG_A, "IELTS", AUTHOR_A, "DRAFT", d1);
        UUID m2 = item(ORG_A, "IELTS", AUTHOR_A, "DRAFT", d2);
        UUID m3 = item(ORG_A, "IELTS", AUTHOR_A, "DRAFT", m1);   // depends on a member that itself fails
        UUID bundle = as(AUTHOR_A, ORG_A, () -> reviews.submit(new BundleRef(List.of(ref(m1), ref(m2), ref(m3)))));
        UnpublishedDependencyException e = assertThrows(UnpublishedDependencyException.class, () -> asDo(REVIEWER_A, ORG_A, () -> reviews.approveBundle(bundle)));
        assertEquals(Set.of(ref(d1), ref(d2)), Set.copyOf(e.missing()), "both outside dependencies, no member of the bundle itself");
        assertEquals(2, e.missing().size());
    }

    @Test
    void otherTenantsCannotDecideOrSeeABundle() {
        UUID a = item(ORG_A, "IELTS", AUTHOR_A, "DRAFT");
        UUID b = item(ORG_A, "IELTS", AUTHOR_A, "DRAFT");
        UUID bundle = as(AUTHOR_A, ORG_A, () -> reviews.submit(new BundleRef(List.of(ref(a), ref(b)))));
        for (UUID org : new UUID[] {ORG_B, null}) {
            assertThrows(ReviewNotFoundException.class, () -> asDo(REVIEWER_B, org, () -> reviews.approveBundle(bundle)));
            assertThrows(ReviewNotFoundException.class, () -> asDo(REVIEWER_B, org, () -> reviews.requestBundleChanges(bundle, "x")));
        }
        assertThrows(ReviewNotFoundException.class, () -> asDo(E9_REVIEWER, ORG_A, () -> reviews.approveBundle(bundle)));
        assertEquals(List.of("IN_REVIEW", "IN_REVIEW"), List.of(status(a), status(b)));
        // row-level security of review_bundles itself
        assertEquals(1, bundlesVisibleTo(AUTHOR_A, ORG_A, bundle));
        assertEquals(0, bundlesVisibleTo(REVIEWER_B, ORG_B, bundle));
        assertEquals(0, bundlesVisibleTo(E9_REVIEWER, null, bundle));
        assertThrows(Exception.class, () -> as(AUTHOR_B, ORG_B, () -> new TransactionTemplate(tm).execute(st ->
                appJdbc.update("insert into review_bundles(owner_org_id, submitted_by, member_count) values (?,?,1)", ORG_A, AUTHOR_B))),
                "a bundle cannot be filed into another organisation");
    }

    @Test
    void anOuterTransactionRolledBackAfterPublicationDeliversNothing() {
        UUID it = item(ORG_A, "IELTS", AUTHOR_A, "DRAFT");
        UUID review = submitted(AUTHOR_A, ORG_A, it);
        recorder.workflowEvents().clear();
        as(REVIEWER_A, ORG_A, () -> new TransactionTemplate(tm).execute(st -> {
            reviews.approve(review);
            st.setRollbackOnly();
            return null;
        }));
        assertEquals(List.of(true), recorder.publishedInsideTransaction(), "it was published inside the transaction ...");
        assertEquals(List.of(), recorder.committed(), "... and an AFTER_COMMIT listener never saw it");
        assertEquals(List.of(), recorder.workflowEvents());
        assertEquals("IN_REVIEW", status(it));
        assertEquals("IN_REVIEW", reviewStatus(review));
    }

    // ---- the audit log (P1.9, F57): every submit and decision is recorded in the same transaction, by the person who did it --------

    private static List<java.util.Map<String, Object>> audit(String action, Object target) {
        return su.queryForList("select actor_id, org_id, target_type, target_id, detail::text as detail from audit_log where action = ? and target_id = ? order by seq",
                action, target.toString());
    }

    @Test
    void everySubmitAndDecisionIsRecordedByItsActorWithoutTheCommentText() {
        UUID it = item(ORG_D, "IELTS", AUTHOR_D, "DRAFT");
        UUID review = submitted(AUTHOR_D, ORG_D, it);
        var submit = audit("review.submit", review);
        assertEquals(1, submit.size());
        assertEquals(AUTHOR_D, submit.get(0).get("actor_id"));
        assertEquals(ORG_D, submit.get(0).get("org_id"));
        assertEquals("review", submit.get(0).get("target_type"));
        assertTrue(((String) submit.get(0).get("detail")).contains(it.toString()) && ((String) submit.get(0).get("detail")).contains("IELTS"), submit.get(0).toString());

        String comment = "PRIVATE-FEEDBACK-" + UUID.randomUUID();
        asDo(REVIEWER_D, ORG_D, () -> reviews.requestChanges(review, comment));
        var changes = audit("review.request-changes", review);
        assertEquals(1, changes.size());
        assertEquals(REVIEWER_D, changes.get(0).get("actor_id"));
        assertTrue(((String) changes.get(0).get("detail")).contains("\"commentLength\": " + comment.length()), changes.get(0).toString());
        assertEquals(0, su.queryForObject("select count(*) from audit_log where row_to_json(audit_log)::text like ?", Integer.class, "%PRIVATE-FEEDBACK%"),
                "the comment text never reaches the log, only its length");

        UUID review2 = submitted(AUTHOR_D, ORG_D, it);
        asDo(REVIEWER_D, ORG_D, () -> reviews.approve(review2));
        var approved = audit("review.approve", review2);
        assertEquals(1, approved.size());
        assertEquals(REVIEWER_D, approved.get(0).get("actor_id"));
        assertEquals(ORG_D, approved.get(0).get("org_id"));
        assertTrue(((String) approved.get(0).get("detail")).contains(it.toString()), approved.get(0).toString());
    }

    @Test
    void bundlesAreRecordedWithTheirMemberCount() {
        UUID m1 = item(ORG_D, "IELTS", AUTHOR_D, "DRAFT");
        UUID m2 = item(ORG_D, "IELTS", AUTHOR_D, "DRAFT");
        UUID bundle = as(AUTHOR_D, ORG_D, () -> reviews.submit(new BundleRef(List.of(ref(m1), ref(m2)))));
        var submit = audit("review.submit-bundle", bundle);
        assertEquals(1, submit.size());
        assertEquals(AUTHOR_D, submit.get(0).get("actor_id"));
        assertTrue(((String) submit.get(0).get("detail")).contains("\"members\": 2"), submit.get(0).toString());
        asDo(REVIEWER_D, ORG_D, () -> reviews.requestBundleChanges(bundle, "BUNDLE-PRIVATE-NOTE"));
        assertEquals(1, audit("review.request-bundle-changes", bundle).size());
        assertEquals(REVIEWER_D, audit("review.request-bundle-changes", bundle).get(0).get("actor_id"));
        UUID bundle2 = as(AUTHOR_D, ORG_D, () -> reviews.submit(new BundleRef(List.of(ref(m1), ref(m2)))));
        asDo(REVIEWER_D, ORG_D, () -> reviews.approveBundle(bundle2));
        var approved = audit("review.approve-bundle", bundle2);
        assertEquals(1, approved.size());
        assertTrue(((String) approved.get(0).get("detail")).contains("\"members\": 2"), approved.get(0).toString());
        assertEquals(0, su.queryForObject("select count(*) from audit_log where row_to_json(audit_log)::text like ?", Integer.class, "%BUNDLE-PRIVATE-NOTE%"));
    }

    @Test
    void refusedAndRolledBackDecisionsLeaveNoRecord() {
        long before = su.queryForObject("select count(*) from audit_log where action like 'review.%' and actor_id in (?, ?, ?)", Long.class, DUAL_A, REVIEWER_A, AUTHOR_A);
        UUID mine = item(ORG_A, "IELTS", DUAL_A, "DRAFT");
        UUID review = submitted(DUAL_A, ORG_A, mine);           // the submit itself is recorded
        assertThrows(SelfReviewException.class, () -> asDo(DUAL_A, ORG_A, () -> reviews.approve(review)));
        assertThrows(SelfReviewException.class, () -> asDo(DUAL_A, ORG_A, () -> reviews.requestChanges(review, "x")));
        assertEquals(List.of(), audit("review.approve", review));
        assertEquals(List.of(), audit("review.request-changes", review));
        // rolled back by an outer transaction: the decision and its record go together
        UUID other = item(ORG_A, "IELTS", AUTHOR_A, "DRAFT");
        UUID review2 = submitted(AUTHOR_A, ORG_A, other);
        as(REVIEWER_A, ORG_A, () -> new TransactionTemplate(tm).execute(st -> {
            reviews.approve(review2);
            assertEquals(1, appJdbc.queryForObject("select count(*) from review_requests where id = ? and status = 'PUBLISHED'", Integer.class, review2));
            st.setRollbackOnly();
            return null;
        }));
        assertEquals(List.of(), audit("review.approve", review2));
        assertEquals(before + 2, su.queryForObject("select count(*) from audit_log where action like 'review.%' and actor_id in (?, ?, ?)", Long.class, DUAL_A, REVIEWER_A, AUTHOR_A),
                "only the two submits are on record");
    }

    @Test
    void aReviewActionFailsWhenItsRecordCannotBeWritten() {
        UUID it = item(ORG_D, "IELTS", AUTHOR_D, "DRAFT");
        UUID pending = submitted(AUTHOR_D, ORG_D, it);
        UUID m1 = item(ORG_D, "IELTS", AUTHOR_D, "DRAFT");
        UUID bundlePending = as(AUTHOR_D, ORG_D, () -> reviews.submit(new BundleRef(List.of(ref(m1)))));
        UUID fresh = item(ORG_D, "IELTS", AUTHOR_D, "DRAFT");
        UUID m2 = item(ORG_D, "IELTS", AUTHOR_D, "DRAFT");
        su.execute("revoke insert on audit_log from e9x_app");
        try {
            assertThrows(RuntimeException.class, () -> as(AUTHOR_D, ORG_D, () -> reviews.submit(ref(fresh))));
            assertThrows(RuntimeException.class, () -> as(AUTHOR_D, ORG_D, () -> reviews.submit(new BundleRef(List.of(ref(m2))))));
            assertThrows(RuntimeException.class, () -> asDo(REVIEWER_D, ORG_D, () -> reviews.approve(pending)));
            assertThrows(RuntimeException.class, () -> asDo(REVIEWER_D, ORG_D, () -> reviews.requestChanges(pending, "x")));
            assertThrows(RuntimeException.class, () -> asDo(REVIEWER_D, ORG_D, () -> reviews.approveBundle(bundlePending)));
            assertThrows(RuntimeException.class, () -> asDo(REVIEWER_D, ORG_D, () -> reviews.requestBundleChanges(bundlePending, "x")));
        } finally {
            su.execute("grant insert (action, target_type, target_id, detail) on audit_log to e9x_app");
        }
        assertEquals("IN_REVIEW", reviewStatus(pending), "the decision was rolled back with the failed record");
        assertEquals(Set.of("IN_REVIEW"), Set.copyOf(reviewIdsOfBundle(bundlePending).stream().map(ReviewServiceIT::reviewStatus).toList()));
        assertEquals("DRAFT", status(fresh), "a refused submit left the version a draft");
        assertEquals("DRAFT", status(m2));
        // the positive control: with the grant back the same submit goes through, so what failed above was the record and nothing else
        UUID reviewOfFresh = as(AUTHOR_D, ORG_D, () -> reviews.submit(ref(fresh)));
        assertEquals("IN_REVIEW", reviewStatus(reviewOfFresh));
    }
}
