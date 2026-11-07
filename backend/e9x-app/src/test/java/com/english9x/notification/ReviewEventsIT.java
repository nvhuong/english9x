package com.english9x.notification;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.english9x.org.api.CurrentContextHolder;
import com.english9x.support.TestAuth;
import com.english9x.workflow.api.events.ReviewApproved;
import com.english9x.workflow.api.events.ReviewChangesRequested;
import com.english9x.workflow.api.events.ReviewRequested;
import java.time.Duration;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;
import org.awaitility.Awaitility;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * The workflow events become notifications: ReviewRequested to the REVIEWERs of the exam in the content's own context, the two
 * decisions to the author. Events are published the way the workflow does (inside the business transaction) and delivered by Spring
 * Modulith's persistent registry, at least once; the consumer is idempotent.
 */
class ReviewEventsIT extends NotificationIT {

    @Autowired ApplicationEventPublisher events;
    @Autowired PlatformTransactionManager tm;

    UUID orgA;
    UUID orgB;
    UUID author;
    UUID submitter;
    UUID reviewerA1;
    UUID reviewerA2;
    UUID reviewerToeic;
    UUID reviewerB;
    UUID reviewerGlobal;
    UUID adminA;

    @BeforeEach
    void world() {
        su.update("insert into exams(code,name) values ('IELTS','IELTS'),('TOEIC','TOEIC') on conflict do nothing");
        orgA = org("CENTER");
        orgB = org("CENTER");
        author = user("author");
        submitter = user("submitter");
        grant(author, orgA, "IELTS", "AUTHOR");
        grant(submitter, orgA, "IELTS", "AUTHOR");
        reviewerA1 = user("revA1");
        reviewerA2 = user("revA2", "en", "Reviewer Two");
        reviewerToeic = user("revToeic");
        reviewerB = user("revB");
        reviewerGlobal = user("revGlobal");
        adminA = user("adminA");
        grant(reviewerA1, orgA, "IELTS", "REVIEWER");
        grant(reviewerA2, orgA, "IELTS", "REVIEWER");
        grant(reviewerToeic, orgA, "TOEIC", "REVIEWER");
        grant(reviewerB, orgB, "IELTS", "REVIEWER");
        grant(reviewerGlobal, null, "IELTS", "REVIEWER");
        grant(adminA, orgA, null, "ORG_ADMIN");
    }

    @AfterEach
    void dropTestTriggers() {
        su.execute("drop trigger if exists it_fail_trg on notifications");
        su.execute("drop trigger if exists it_ctx_trg on notifications");
    }

    private UUID review(UUID owner, String exam, String status) {
        UUID id = UUID.randomUUID();
        su.update("insert into review_requests(id,content_type,version_id,owner_org_id,exam_code,author_id,submitted_by,status) "
                + "values (?,?,?,?,?,?,?,'IN_REVIEW')", id, "item", UUID.randomUUID(), owner, exam, author, submitter);
        if (!"IN_REVIEW".equals(status)) {
            su.update("update review_requests set status = ?, reviewer_id = ?, decided_at = now(), comment = 'c' where id = ?", status,
                    reviewerA1, id);
        }
        return id;
    }

    private void publish(Object event) {
        new TransactionTemplate(tm).executeWithoutResult(s -> events.publishEvent(event));
    }

    private ReviewRequested requested(UUID owner, String exam, UUID reviewId) {
        return new ReviewRequested("item", UUID.randomUUID(), Optional.ofNullable(owner), exam, reviewId, Optional.empty());
    }

    private Set<UUID> notified(String type, String reviewId) {
        return su.queryForList("select user_id::text from notifications where event_type = ? and data ->> 'reviewId' = ?", String.class, type, reviewId)
                .stream().map(UUID::fromString).collect(Collectors.toSet());
    }

    /** A negative needs proof that the listener has finished: its publication row is deleted when it completes. */
    private void awaitListenersCompleted(UUID reviewId) {
        Awaitility.await().atMost(Duration.ofSeconds(20)).untilAsserted(() -> assertEquals(0,
                su.queryForObject("select count(*) from event_publication where serialized_event like ?", Integer.class, "%" + reviewId + "%"),
                "the listeners of the event have completed"));
    }

    private void awaitCount(String type, String reviewId, int n) {
        Awaitility.await().atMost(Duration.ofSeconds(20)).untilAsserted(() -> assertEquals(n, notified(type, reviewId).size()));
    }

    @Test
    void reviewRequestedGoesToTheReviewersOfTheExamInTheContentsContext() {
        UUID r = review(orgA, "IELTS", "IN_REVIEW");
        publish(requested(orgA, "IELTS", r));
        awaitCount("REVIEW_REQUESTED", r.toString(), 2);
        assertEquals(Set.of(reviewerA1, reviewerA2), notified("REVIEW_REQUESTED", r.toString()),
                "not the TOEIC reviewer, not another organisation's, not English9x's, not the author/submitter, not the org admin");
        // the English9x context: only English9x reviewers of the exam
        UUID g = review(null, "IELTS", "IN_REVIEW");
        publish(requested(null, "IELTS", g));
        awaitCount("REVIEW_REQUESTED", g.toString(), 1);
        assertEquals(Set.of(reviewerGlobal), notified("REVIEW_REQUESTED", g.toString()));
        // the mail reached the English-speaking reviewer in English
        assertTrue(TestAuth.SMTP.await(emailOf(reviewerA2)).subject().startsWith("Content is waiting"));
    }

    @Test
    void theNotificationCarriesNoContentTitleOnlyIdsExamAndType() {
        UUID r = review(orgA, "IELTS", "IN_REVIEW");
        publish(requested(orgA, "IELTS", r));
        awaitCount("REVIEW_REQUESTED", r.toString(), 2);
        String data = su.queryForObject("select data::text from notifications where user_id = ? and data ->> 'reviewId' = ?", String.class, reviewerA1, r.toString());
        assertTrue(data.contains("\"examCode\""), data);
        assertEquals(Set.of("contentType", "examCode", "reviewId", "orgId"), toKeys(data));
    }

    private static Set<String> toKeys(String json) {
        try {
            Set<String> keys = new java.util.HashSet<>();
            JSON.readTree(json).fieldNames().forEachRemaining(keys::add);
            return keys;
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }

    @Test
    void decisionsGoToTheAuthor() {
        UUID r = review(orgA, "IELTS", "IN_REVIEW");
        publish(new ReviewChangesRequested("item", UUID.randomUUID(), Optional.of(orgA), "IELTS", r, Optional.empty(), author));
        awaitCount("REVIEW_CHANGES_REQUESTED", r.toString(), 1);
        assertEquals(Set.of(author), notified("REVIEW_CHANGES_REQUESTED", r.toString()));
        UUID r2 = review(orgA, "IELTS", "IN_REVIEW");
        publish(new ReviewApproved("item", UUID.randomUUID(), Optional.of(orgA), "IELTS", r2, Optional.empty(), author));
        awaitCount("CONTENT_PUBLISHED", r2.toString(), 1);
        assertEquals(Set.of(author), notified("CONTENT_PUBLISHED", r2.toString()));
    }

    @Test
    void aRolledBackTransactionNotifiesNobody() {
        UUID r = review(orgA, "IELTS", "IN_REVIEW");
        try {
            new TransactionTemplate(tm).executeWithoutResult(s -> {
                events.publishEvent(requested(orgA, "IELTS", r));
                throw new IllegalStateException("business failure");
            });
        } catch (IllegalStateException expected) {
            // rolled back
        }
        // the publication row is written in the business transaction, so a rollback leaves none: no event exists that a listener could ever see
        assertEquals(0, su.queryForObject("select count(*) from event_publication where serialized_event like ?", Integer.class, "%" + r + "%"));
        assertEquals(0, notified("REVIEW_REQUESTED", r.toString()).size());
    }

    @Test
    void aRedeliveredEventNeverNotifiesTwice() {
        UUID r = review(orgA, "IELTS", "IN_REVIEW");
        ReviewRequested e = requested(orgA, "IELTS", r);
        publish(e);
        awaitCount("REVIEW_REQUESTED", r.toString(), 2);
        // a second publication of the very same event (what a redelivery after a crash looks like to the consumer)
        publish(e);
        awaitListenersCompleted(r);
        assertEquals(2, notified("REVIEW_REQUESTED", r.toString()).size());
        assertEquals(2, su.queryForObject("select count(*) from notifications where data ->> 'reviewId' = ?", Integer.class, r.toString()));
        assertEquals(1, TestAuth.SMTP.to(emailOf(reviewerA1)).size(), "one mail, not two");
    }

    @Test
    void aListenerThatFailedIsRedeliveredByTheRegistryAndStillNotifiesOnce() {
        UUID r = review(orgA, "IELTS", "IN_REVIEW");
        su.execute("create table if not exists it_fail_flag(on_ boolean)");
        su.execute("create or replace function it_fail() returns trigger language plpgsql security definer as $$ begin "
                + "if exists (select 1 from it_fail_flag) then raise exception 'it: listener failure'; end if; return new; end $$");
        su.execute("drop trigger if exists it_fail_trg on notifications");
        su.execute("create trigger it_fail_trg before insert on notifications for each row execute function it_fail()");
        su.update("delete from it_fail_flag");
        su.update("insert into it_fail_flag values (true)");
        try {
            publish(requested(orgA, "IELTS", r));
            Awaitility.await().atMost(Duration.ofSeconds(10)).untilAsserted(() -> assertEquals(1,
                    su.queryForObject("select count(*) from event_publication where serialized_event like ? and completion_date is null", Integer.class, "%" + r + "%"),
                    "the publication is kept while the listener has not completed"));
            assertEquals(0, notified("REVIEW_REQUESTED", r.toString()).size());
        } finally {
            su.update("delete from it_fail_flag");
        }
        try {
            awaitCount("REVIEW_REQUESTED", r.toString(), 2);
        } finally {
            su.execute("drop trigger if exists it_fail_trg on notifications");
        }
        Awaitility.await().atMost(Duration.ofSeconds(10)).untilAsserted(() -> assertEquals(0,
                su.queryForObject("select count(*) from event_publication where serialized_event like ?", Integer.class, "%" + r + "%"),
                "completed publications are deleted"));
        su.execute("drop trigger if exists it_fail_trg on notifications");
        assertEquals(2, notified("REVIEW_REQUESTED", r.toString()).size());
    }

    @Test
    void aBindingLeftOnTheCallingThreadDoesNotReachTheListenersTransaction() {
        UUID r = review(orgA, "IELTS", "IN_REVIEW");
        su.execute("create table if not exists it_ctx_log(user_id text, review text)");
        su.execute("create or replace function it_ctx() returns trigger language plpgsql security definer as $$ begin "
                + "insert into it_ctx_log values (coalesce(current_setting('app.user_id', true), '<null>'), new.data ->> 'reviewId'); return new; end $$");
        su.execute("drop trigger if exists it_ctx_trg on notifications");
        su.execute("create trigger it_ctx_trg before insert on notifications for each row execute function it_ctx()");
        List<String> seen;
        try (CurrentContextHolder.Scope ignored = CurrentContextHolder.bind(reviewerB, orgB)) {
            publish(requested(orgA, "IELTS", r));
        }
        try {
            awaitCount("REVIEW_REQUESTED", r.toString(), 2);
            seen = su.queryForList("select user_id from it_ctx_log where review = ?", String.class, r.toString());
        } finally {
            su.execute("drop trigger if exists it_ctx_trg on notifications");
        }
        assertEquals(2, seen.size());
        assertTrue(seen.stream().allMatch(s -> s.isEmpty() || s.equals("<null>")), "the listener ran with no user bound, not as " + seen);
    }

    @Test
    void aReviewThatIsNoLongerOpenOrUnknownNotifiesNobody() {
        UUID decided = review(orgA, "IELTS", "PUBLISHED");
        publish(requested(orgA, "IELTS", decided));
        UUID ghost = UUID.randomUUID();
        publish(requested(orgA, "IELTS", ghost));
        awaitListenersCompleted(decided);
        awaitListenersCompleted(ghost);
        assertEquals(0, notified("REVIEW_REQUESTED", decided.toString()).size());
        assertEquals(0, notified("REVIEW_REQUESTED", ghost.toString()).size());
    }
}
