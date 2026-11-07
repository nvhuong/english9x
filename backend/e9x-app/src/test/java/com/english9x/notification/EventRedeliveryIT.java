package com.english9x.notification;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.english9x.workflow.api.events.ReviewRequested;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import org.awaitility.Awaitility;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * Redelivery of outstanding module events is per publication: a row that cannot be read back is logged (id, type, listener; never
 * the payload) and skipped, the others are delivered in the same cycle; a publication that never completes is deleted once it is
 * older than {@code e9x.events.publication-max-age} (30 days) so a failing row cannot keep personal data for ever.
 */
@ExtendWith(OutputCaptureExtension.class)
class EventRedeliveryIT extends NotificationIT {

    @Autowired ApplicationEventPublisher events;
    @Autowired PlatformTransactionManager tm;
    @Autowired JdbcTemplate app;

    UUID org;
    UUID reviewer;

    @BeforeEach
    void world() {
        su.update("insert into exams(code,name) values ('IELTS','IELTS') on conflict do nothing");
        org = org("CENTER");
        reviewer = user("rev");
        grant(reviewer, org, "IELTS", "REVIEWER");
    }

    @AfterEach
    void cleanBadRows() {
        su.update("delete from event_publication where serialized_event not like '{%' or listener_id like 'it-bad-%'");
    }

    private UUID review() {
        UUID author = user("author");
        UUID id = UUID.randomUUID();
        su.update("insert into review_requests(id,content_type,version_id,owner_org_id,exam_code,author_id,submitted_by,status) "
                + "values (?,?,?,?,?,?,?,'IN_REVIEW')", id, "item", UUID.randomUUID(), org, "IELTS", author, author);
        return id;
    }

    /** What the registry would have stored for this event and its listener, taken from a transaction that is rolled back. */
    private Map<String, Object> rowFor(UUID reviewId) {
        return new TransactionTemplate(tm).execute(s -> {
            events.publishEvent(new ReviewRequested("item", UUID.randomUUID(), Optional.of(org), "IELTS", reviewId, Optional.empty()));
            Map<String, Object> row = app.queryForMap("select listener_id, event_type, serialized_event from event_publication "
                    + "where serialized_event like ?", "%" + reviewId + "%");
            s.setRollbackOnly();
            return row;
        });
    }

    private void insertOutstanding(UUID id, Map<String, Object> like, String serialized, String ageSql) {
        su.update("insert into event_publication(id,listener_id,event_type,serialized_event,publication_date) values (?,?,?,?, now() - " + ageSql + ")",
                id, like.get("listener_id"), like.get("event_type"), serialized);
    }

    private int notifications(UUID reviewId) {
        return su.queryForObject("select count(*) from notifications where data ->> 'reviewId' = ?", Integer.class, reviewId.toString());
    }

    private int rows(UUID id) {
        return su.queryForObject("select count(*) from event_publication where id = ?", Integer.class, id);
    }

    @Test
    void aBadRowDoesNotStopTheGoodOnesAndStaysLoggedWithoutItsPayload(CapturedOutput output) {
        UUID review = review();
        Map<String, Object> like = rowFor(review);
        UUID good = UUID.randomUUID();
        UUID bad = UUID.randomUUID();
        insertOutstanding(bad, like, "not json SECRET-PAYLOAD", "interval '1 hour'");
        insertOutstanding(good, like, (String) like.get("serialized_event"), "interval '1 hour'");

        Awaitility.await().atMost(Duration.ofSeconds(20)).untilAsserted(() -> assertEquals(0, rows(good), "the good row was delivered"));
        assertEquals(1, rows(bad), "the bad row is still there");
        assertEquals(1, notifications(review));
        Awaitility.await().atMost(Duration.ofSeconds(10)).until(() -> output.getAll().contains(bad.toString()));
        String log = output.getAll();
        assertTrue(log.contains(bad.toString()), "the log names the publication");
        assertTrue(log.contains((String) like.get("event_type")), "and its event type");
        assertTrue(log.contains((String) like.get("listener_id")), "and its listener");
        assertFalse(log.contains("SECRET-PAYLOAD"), "never the payload");
    }

    @Test
    void aPublicationThatNeverCompletedIsDeletedOnceOlderThanTheMaximumAge(CapturedOutput output) {
        UUID review = review();
        Map<String, Object> like = rowFor(review);
        UUID old = UUID.randomUUID();
        UUID young = UUID.randomUUID();
        insertOutstanding(old, like, "not json OLD-PAYLOAD", "interval '31 days'");
        insertOutstanding(young, like, "not json YOUNG-PAYLOAD", "interval '29 days'");

        Awaitility.await().atMost(Duration.ofSeconds(20)).untilAsserted(() -> assertEquals(0, rows(old)));
        assertEquals(1, rows(young), "younger than the limit stays");
        assertTrue(output.getAll().contains(old.toString()), "the deletion is logged with the id");
        assertFalse(output.getAll().contains("OLD-PAYLOAD"));
    }

    @Test
    void aWarningCountsTheRowsOlderThanTwiceTheRedeliveryAge(CapturedOutput output) {
        UUID review = review();
        Map<String, Object> like = rowFor(review);
        for (int i = 0; i < 3; i++) {
            insertOutstanding(UUID.randomUUID(), like, "not json " + i, "interval '1 hour'");
        }
        Awaitility.await().atMost(Duration.ofSeconds(20))
                .until(() -> output.getAll().contains("3 event publications are older than"));
    }

    @Test
    void aGoodRowAloneIsStillDelivered() {
        UUID review = review();
        Map<String, Object> like = rowFor(review);
        UUID good = UUID.randomUUID();
        insertOutstanding(good, like, (String) like.get("serialized_event"), "interval '1 hour'");
        Awaitility.await().atMost(Duration.ofSeconds(20)).untilAsserted(() -> assertEquals(0, rows(good)));
        assertEquals(1, notifications(review));
        assertEquals(List.of(), su.queryForList("select id from event_publication where id = ?", good));
    }
}
