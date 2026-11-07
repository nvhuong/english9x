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
import com.english9x.workflow.api.ReviewConflictException;
import com.english9x.workflow.api.ReviewNotFoundException;
import com.english9x.workflow.api.ReviewService;
import com.english9x.workflow.api.ReviewView;
import com.english9x.workflow.api.SelfReviewException;
import com.english9x.workflow.api.UnpublishedDependencyException;
import com.english9x.workflow.api.VersionRef;
import com.english9x.workflow.api.events.ContentPublished;
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
import org.springframework.transaction.event.TransactionPhase;
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

        @EventListener
        void now(ContentPublished e) {
            publishedInsideTransaction.add(TransactionSynchronizationManager.isActualTransactionActive());
        }

        @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT)
        void afterCommit(ContentPublished e) {
            committed.add(e);
        }

        List<ContentPublished> forVersion(UUID id) {
            return committed.stream().filter(e -> e.versionId().equals(id)).toList();
        }
    }

    @Autowired ReviewService reviews;
    @Autowired TestItems items;
    @Autowired Recorder recorder;
    static JdbcTemplate su;

    @BeforeAll
    static void fixtures(@Autowired JdbcTemplate migrated) {
        su = new JdbcTemplate(TestPostgres.superuser(DB));
        su.update("insert into exams(code,name) values ('IELTS','IELTS'),('TOEIC','TOEIC') on conflict do nothing");
        su.update("insert into organizations(id,name,type) values (?,'A','CENTER'),(?,'B','CENTER')", ORG_A, ORG_B);
        for (UUID u : new UUID[] {AUTHOR_A, AUTHOR_A_BOTH, REVIEWER_A, REVIEWER_A2, REVIEWER_A_TOEIC, DUAL_A, LEARNER_A, AUTHOR_B,
                REVIEWER_B, E9_AUTHOR, E9_REVIEWER, SYS_DUAL}) {
            su.update("insert into users(id,email) values (?,?)", u, u + "@x.test");
        }
        for (UUID u : new UUID[] {AUTHOR_A, AUTHOR_A_BOTH, REVIEWER_A, REVIEWER_A2, REVIEWER_A_TOEIC, DUAL_A, LEARNER_A, E9_REVIEWER}) {
            su.update("insert into org_memberships(org_id,user_id) values (?,?)", ORG_A, u);
        }
        for (UUID u : new UUID[] {AUTHOR_B, REVIEWER_B}) {
            su.update("insert into org_memberships(org_id,user_id) values (?,?)", ORG_B, u);
        }
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
        su.execute("create trigger test_items_lifecycle before update or delete on test_items for each row execute function e9x_guard_version_lifecycle()");
        su.execute("grant select, insert, update, delete on test_items, test_item_deps to e9x_app");
    }

    static void grant(UUID user, UUID org, String exam, String role) {
        su.update("insert into role_grants(user_id,org_id,exam_code,role) values (?,?,?,?)", user, org, exam, role);
    }

    @BeforeEach
    void reset() {
        recorder.committed.clear();
        recorder.publishedInsideTransaction.clear();
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
        assertEquals(List.of(), recorder.committed);

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
        assertEquals(List.of(), recorder.committed);

        // an independent reviewer approves it
        asDo(E9_REVIEWER, null, () -> reviews.approve(review3));
        assertEquals("PUBLISHED", status(sys));
    }

    @Test
    void changesRequestedReturnsToDraftWithComment() {
        UUID it = item(ORG_A, "IELTS", AUTHOR_A, "DRAFT");
        UUID review = submitted(AUTHOR_A, ORG_A, it);
        assertEquals("IN_REVIEW", status(it));

        assertThrows(IllegalArgumentException.class, () -> asDo(REVIEWER_A, ORG_A, () -> reviews.requestChanges(review, "   ")), "a comment is required");
        assertThrows(IllegalArgumentException.class, () -> asDo(REVIEWER_A, ORG_A, () -> reviews.requestChanges(review, null)));
        assertEquals("IN_REVIEW", status(it));

        asDo(REVIEWER_A, ORG_A, () -> reviews.requestChanges(review, "Option C is also correct"));
        assertEquals("CHANGES_REQUESTED", status(it), "back with the author, editable again");
        ReviewView v = as(AUTHOR_A, ORG_A, () -> reviews.find(review)).orElseThrow();
        assertEquals(ContentStatus.CHANGES_REQUESTED, v.status());
        assertEquals(Optional.of("Option C is also correct"), v.comment());
        assertEquals(Optional.of(REVIEWER_A), v.reviewerId());
        assertTrue(v.decidedAt().isPresent());
        assertEquals(List.of(), recorder.committed, "nothing was published");

        // the author edits (allowed in this state), submits again: a new review, then approval publishes it once
        su.update("update test_items set body = 'v2' where id = ?", it);
        UUID second = submitted(AUTHOR_A, ORG_A, it);
        assertFalse(second.equals(review));
        assertEquals("IN_REVIEW", status(it));
        asDo(REVIEWER_A, ORG_A, () -> reviews.approve(second));
        assertEquals("PUBLISHED", status(it));
        assertEquals(List.of(new ContentPublished("item", it, Optional.of(ORG_A), "IELTS")), recorder.committed);
        assertEquals(List.of(true), recorder.publishedInsideTransaction, "published inside the transaction of the approval");
        // the earlier review keeps its history
        assertEquals("CHANGES_REQUESTED", reviewStatus(review));

        // a decided review is final
        assertThrows(ReviewConflictException.class, () -> asDo(REVIEWER_A, ORG_A, () -> reviews.approve(review)));
        assertThrows(ReviewConflictException.class, () -> asDo(REVIEWER_A, ORG_A, () -> reviews.approve(second)));
        assertEquals(1, recorder.committed.size());
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
        assertEquals(List.of(), recorder.committed);

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
        assertEquals(3, recorder.committed.size());
        assertEquals(Set.of(q, lesson, course), Set.copyOf(recorder.committed.stream().map(ContentPublished::versionId).toList()));
        assertThrows(ReviewConflictException.class, () -> asDo(REVIEWER_A, ORG_A, () -> reviews.approveBundle(bundle)), "once");
        assertEquals(3, recorder.committed.size());

        // one member depends on something unpublished outside the bundle: nothing is published, no event
        recorder.committed.clear();
        UUID outside = item(ORG_A, "IELTS", AUTHOR_A, "DRAFT");
        UUID m1 = item(ORG_A, "IELTS", AUTHOR_A, "DRAFT");
        UUID m2 = item(ORG_A, "IELTS", AUTHOR_A, "DRAFT");
        UUID m3 = item(ORG_A, "IELTS", AUTHOR_A, "DRAFT", outside);
        UUID b2 = as(AUTHOR_A, ORG_A, () -> reviews.submit(new BundleRef(List.of(ref(m1), ref(m2), ref(m3)))));
        UnpublishedDependencyException e = assertThrows(UnpublishedDependencyException.class, () -> asDo(REVIEWER_A, ORG_A, () -> reviews.approveBundle(b2)));
        assertEquals(List.of(ref(outside)), e.missing());
        assertEquals(List.of("IN_REVIEW", "IN_REVIEW", "IN_REVIEW"), List.of(status(m1), status(m2), status(m3)));
        assertEquals(Set.of("IN_REVIEW"), Set.copyOf(reviewIdsOfBundle(b2).stream().map(ReviewServiceIT::reviewStatus).toList()));
        assertEquals(List.of(), recorder.committed);

        // one member was written by the reviewer: none is published
        UUID n1 = item(ORG_A, "IELTS", AUTHOR_A, "DRAFT");
        UUID n2 = item(ORG_A, "IELTS", DUAL_A, "DRAFT");
        UUID b3 = as(AUTHOR_A, ORG_A, () -> reviews.submit(new BundleRef(List.of(ref(n1), ref(n2)))));
        assertThrows(SelfReviewException.class, () -> asDo(DUAL_A, ORG_A, () -> reviews.approveBundle(b3)));
        assertEquals(List.of("IN_REVIEW", "IN_REVIEW"), List.of(status(n1), status(n2)));
        assertEquals(List.of(), recorder.committed);

        // a member of an exam the reviewer does not review: refused as a whole (they cannot even see that member)
        UUID i1 = item(ORG_A, "IELTS", AUTHOR_A_BOTH, "DRAFT");
        UUID t1 = item(ORG_A, "TOEIC", AUTHOR_A_BOTH, "DRAFT");
        UUID b4 = as(AUTHOR_A_BOTH, ORG_A, () -> reviews.submit(new BundleRef(List.of(ref(i1), ref(t1)))));
        assertThrows(AccessDeniedException.class, () -> asDo(REVIEWER_A, ORG_A, () -> reviews.approveBundle(b4)));
        assertThrows(AccessDeniedException.class, () -> asDo(REVIEWER_A_TOEIC, ORG_A, () -> reviews.approveBundle(b4)));
        assertEquals(List.of("IN_REVIEW", "IN_REVIEW"), List.of(status(i1), status(t1)));
        assertEquals(List.of(), recorder.committed);
        // ... and the whole bundle can be sent back, or decided by someone who may decide all of it
        assertThrows(AccessDeniedException.class, () -> asDo(REVIEWER_A, ORG_A, () -> reviews.requestBundleChanges(b4, "no")));
        assertEquals(List.of("IN_REVIEW", "IN_REVIEW"), List.of(status(i1), status(t1)));

        // requesting changes for a bundle returns every member
        UUID r1 = item(ORG_A, "IELTS", AUTHOR_A, "DRAFT");
        UUID r2 = item(ORG_A, "IELTS", AUTHOR_A, "DRAFT");
        UUID b5 = as(AUTHOR_A, ORG_A, () -> reviews.submit(new BundleRef(List.of(ref(r1), ref(r2)))));
        asDo(REVIEWER_A, ORG_A, () -> reviews.requestBundleChanges(b5, "redo both"));
        assertEquals(List.of("CHANGES_REQUESTED", "CHANGES_REQUESTED"), List.of(status(r1), status(r2)));
        assertEquals(2, as(AUTHOR_A, ORG_A, () -> reviews.members(b5)).stream().filter(v -> v.comment().equals(Optional.of("redo both"))).count());
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
        assertThrows(IllegalArgumentException.class, () -> asDo(AUTHOR_A, ORG_A, () -> reviews.submit(new BundleRef(List.of(ref(ok), ref(ok))))));
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

        JdbcTemplate app = new JdbcTemplate(TestPostgres.app(DB));
        assertThrows(DataIntegrityViolationException.class, () -> app.update("update test_items set body = 'changed' where id = ?", it), "the application role");
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
            assertTrue(as(REVIEWER_B, org, () -> reviews.open(200)).stream().noneMatch(v -> v.ownerOrgId().equals(Optional.of(ORG_A))),
                    "B's queue holds no review of A");
            assertThrows(ReviewNotFoundException.class, () -> asDo(REVIEWER_B, org, () -> reviews.approve(review)));
            assertThrows(ReviewNotFoundException.class, () -> asDo(REVIEWER_B, org, () -> reviews.requestChanges(review, "no")));
        }
        assertThrows(ReviewNotFoundException.class, () -> asDo(AUTHOR_B, ORG_B, () -> reviews.approve(review)));
        assertEquals("IN_REVIEW", status(it));
        assertEquals("IN_REVIEW", reviewStatus(review));
        assertEquals(List.of(), recorder.committed);
        // the same call from inside the right organisation works (the positive control)
        assertEquals(1, as(REVIEWER_A, ORG_A, () -> reviews.open(200)).stream().filter(v -> v.id().equals(review)).count());
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
        assertEquals(List.of(), recorder.committed);
        // the open queues are separate
        List<ReviewView> queue = as(E9_REVIEWER, null, () -> reviews.open(200));
        assertTrue(queue.stream().anyMatch(v -> v.id().equals(e9Review)));
        assertTrue(queue.stream().allMatch(v -> v.ownerOrgId().isEmpty()), "the English9x queue holds English9x reviews only");
        assertTrue(as(REVIEWER_A, ORG_A, () -> reviews.open(200)).stream().noneMatch(v -> v.id().equals(e9Review)));
        // English9x publication carries no owner
        asDo(E9_REVIEWER, null, () -> reviews.approve(e9Review));
        assertEquals(List.of(new ContentPublished("item", e9, Optional.empty(), "IELTS")), recorder.committed);
    }

    @Test
    void anExamScopedReviewerOnlyActsOnThatExam() {
        UUID ielts = item(ORG_A, "IELTS", AUTHOR_A, "DRAFT");
        UUID review = submitted(AUTHOR_A, ORG_A, ielts);
        assertThrows(ReviewNotFoundException.class, () -> asDo(REVIEWER_A_TOEIC, ORG_A, () -> reviews.approve(review)));
        assertThrows(ReviewNotFoundException.class, () -> asDo(REVIEWER_A_TOEIC, ORG_A, () -> reviews.requestChanges(review, "x")));
        assertTrue(as(REVIEWER_A_TOEIC, ORG_A, () -> reviews.open(200)).stream().allMatch(v -> v.examCode().equals("TOEIC")),
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
        assertThrows(IllegalArgumentException.class, () -> asDo(AUTHOR_A, ORG_A, () -> reviews.submit(new VersionRef("nonsense", a))));
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
}
