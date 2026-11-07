package com.english9x.notification;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.english9x.support.TestPostgres;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.dao.DataAccessException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.SingleConnectionDataSource;

/**
 * The database side of the privacy rulings, as the two real logins: the request role sees only its own notifications whatever else
 * the session claims, and the worker role reaches no notification table, only the two delivery functions.
 */
class NotificationRolesIT extends NotificationIT {

    private final JdbcTemplate app = new JdbcTemplate(TestPostgres.app(DB));
    private final JdbcTemplate worker = new JdbcTemplate(TestPostgres.worker(DB));

    /** Runs {@code body} on one e9x_app_login connection inside a transaction whose session context is set like the application does. */
    private <T> T asApp(UUID user, UUID org, boolean staff, java.util.function.Function<JdbcTemplate, T> body) {
        try (SingleConnectionDataSource ds = new SingleConnectionDataSource(TestPostgres.jdbcUrl(DB), TestPostgres.APP_LOGIN, TestPostgres.APP_PASSWORD, true)) {
            JdbcTemplate t = new JdbcTemplate(ds);
            return new org.springframework.transaction.support.TransactionTemplate(new org.springframework.jdbc.datasource.DataSourceTransactionManager(ds))
                    .execute(s -> {
                        t.queryForObject("select set_config('app.user_id', ?, true)", String.class, user == null ? "" : user.toString());
                        t.queryForObject("select set_config('app.org_id', ?, true)", String.class, org == null ? "" : org.toString());
                        t.queryForObject("select set_config('app.e9x_staff', ?, true)", String.class, staff ? "on" : "");
                        t.queryForObject("select set_config('app.org_admin', ?, true)", String.class, staff ? "on" : "");
                        return body.apply(t);
                    });
        }
    }

    private int asAppInt(UUID user, UUID org, boolean staff, java.util.function.Function<JdbcTemplate, Integer> body) {
        return asApp(user, org, staff, body);
    }

    private UUID notification(UUID user, String type) {
        return su.queryForList("select r_notification_id from notification_create(?, ?, '{}'::jsonb, ?, now())", UUID.class, user, type, "k-" + UUID.randomUUID())
                .stream().findFirst().orElseGet(() -> su.queryForObject("select id from notifications where user_id = ? order by created_at desc limit 1", UUID.class, user));
    }

    @Test
    void theRequestRoleSeesAndMarksOnlyItsOwnRowsWhateverTheSessionClaims() {
        UUID a = user("dbA");
        UUID b = user("dbB");
        UUID org = org("CENTER");
        grant(a, org, null, "ORG_ADMIN");
        UUID na = notification(a, "REVIEW_REQUESTED");
        UUID nb = notification(b, "REVIEW_REQUESTED");
        // a, even claiming staff and an org context that b belongs to, sees only a's
        List<UUID> seen = asApp(a, org, true, t -> t.queryForList("select id from notifications where user_id in (?, ?)", UUID.class, a, b));
        assertEquals(List.of(na), seen);
        assertEquals(0, asAppInt(a, org, true, t -> t.update("update notifications set read_at = now() where id = ?", nb)), "b's row is not updatable");
        assertEquals(1, asAppInt(a, null, false, t -> t.update("update notifications set read_at = now() where id = ?", na)));
        // no session user: nothing
        assertEquals(0, asAppInt(null, null, false, t -> t.queryForList("select id from notifications", UUID.class).size()));
        // only read_at is updatable, nothing can be deleted or inserted directly
        assertThrows(DataAccessException.class, () -> asApp(a, null, false, t -> t.update("update notifications set data = '{}' where id = ?", na)));
        assertThrows(DataAccessException.class, () -> asApp(a, null, false, t -> t.update("delete from notifications where id = ?", na)));
        assertThrows(DataAccessException.class, () -> asApp(a, null, false,
                t -> t.update("insert into notifications(user_id,event_type,dedupe_key) values (?, 'REVIEW_REQUESTED', 'direct')", a)));
    }

    @Test
    void preferencesAndLinksAreTheirOwners() {
        UUID a = user("dbPrefA");
        UUID b = user("dbPrefB");
        assertEquals(1, asAppInt(a, null, false, t -> t.update("insert into notification_prefs(user_id,event_type,channel,enabled) values (?, 'SCORE_OFFICIAL','EMAIL', false)", a)));
        assertThrows(DataAccessException.class, () -> asApp(a, null, false,
                t -> t.update("insert into notification_prefs(user_id,event_type,channel,enabled) values (?, 'SCORE_OFFICIAL','EMAIL', false)", b)));
        assertEquals(0, asAppInt(b, null, false, t -> t.queryForList("select 1 from notification_prefs where user_id = ?", Integer.class, a).size()));
        linkTelegram(a);
        assertEquals(0, asAppInt(b, null, false, t -> t.queryForList("select 1 from telegram_links", Integer.class).size()));
        assertEquals(0, asAppInt(b, null, false, t -> t.update("delete from telegram_links where user_id = ?", a)));
        for (String table : new String[] {"telegram_link_tokens", "notification_deliveries"}) {
            assertThrows(DataAccessException.class, () -> app.queryForList("select 1 from " + table), table + " is not readable by the request role");
        }
        assertThrows(DataAccessException.class, () -> asApp(a, null, false, t -> t.update("insert into telegram_links(user_id, chat_id) values (?, 1)", a)),
                "a link is only created by consuming a token");
    }

    @Test
    void theWorkerReachesNoNotificationTableOnlyTheTwoDeliveryFunctions() {
        UUID u = user("dbWorker");
        String chat = linkTelegram(u);
        UUID n = notification(u, "REVIEW_REQUESTED");
        for (String table : new String[] {"notifications", "notification_prefs", "telegram_links", "telegram_link_tokens", "notification_deliveries", "users", "review_requests"}) {
            assertThrows(DataAccessException.class, () -> worker.queryForList("select 1 from " + table), table);
        }
        // the e-mail delivery carries the address and no chat id; the Telegram one the chat and no address
        var email = worker.queryForMap("select * from notification_delivery_load(?, 'EMAIL')", n);
        assertEquals(emailOf(u), email.get("r_email"));
        assertNull(email.get("r_chat_id"));
        var tg = worker.queryForMap("select * from notification_delivery_load(?, 'TELEGRAM')", n);
        assertNull(tg.get("r_email"));
        assertEquals(chat, String.valueOf(tg.get("r_chat_id")));
        // finishing is once; afterwards the delivery loads nothing
        assertTrue(worker.queryForObject("select notification_delivery_finish(?, 'EMAIL', 'SENT', null)", Boolean.class, n));
        assertFalse(worker.queryForObject("select notification_delivery_finish(?, 'EMAIL', 'FAILED', 'late')", Boolean.class, n));
        assertEquals(0, worker.queryForList("select * from notification_delivery_load(?, 'EMAIL')", n).size());
        assertEquals(0, worker.queryForList("select * from notification_delivery_load(?, 'EMAIL')", UUID.randomUUID()).size(), "an unknown id is just empty");
        // a person who switched the channel off before the send gets nothing and the delivery is SKIPPED
        setPref(u, com.english9x.notification.api.EventType.REVIEW_REQUESTED, "TELEGRAM", false);
        assertEquals(0, worker.queryForList("select * from notification_delivery_load(?, 'TELEGRAM')", n).size());
        assertEquals("SKIPPED", su.queryForObject("select status from notification_deliveries where notification_id = ? and channel = 'TELEGRAM'", String.class, n));
        // the worker cannot create or look up anything else
        assertThrows(DataAccessException.class, () -> worker.queryForList("select * from notification_create(?, 'X', '{}'::jsonb, 'k', now())", u));
        assertThrows(DataAccessException.class, () -> worker.queryForList("select * from notification_reviewers_of_review(?)", UUID.randomUUID()));
    }

    @Test
    void theRequestRoleCannotCallTheWorkersFunctions() {
        UUID u = user("dbNoWorkerFns");
        UUID n = notification(u, "REVIEW_REQUESTED");
        assertThrows(DataAccessException.class, () -> app.queryForList("select * from notification_delivery_load(?, 'EMAIL')", n));
        assertThrows(DataAccessException.class, () -> app.queryForObject("select notification_delivery_finish(?, 'EMAIL', 'SENT', null)", Boolean.class, n));
    }

    @Test
    void theReviewerLookupIsKeyedByAReviewNotByAnOrganisation() {
        su.update("insert into exams(code,name) values ('IELTS','IELTS') on conflict do nothing");
        UUID orgA = org("CENTER");
        UUID orgB = org("CENTER");
        UUID author = user("lkAuthor");
        UUID rev = user("lkRev");
        UUID revB = user("lkRevB");
        grant(author, orgA, "IELTS", "AUTHOR");
        grant(rev, orgA, "IELTS", "REVIEWER");
        grant(revB, orgB, "IELTS", "REVIEWER");
        UUID review = UUID.randomUUID();
        su.update("insert into review_requests(id,content_type,version_id,owner_org_id,exam_code,author_id,submitted_by) values (?,'item',gen_random_uuid(),?,'IELTS',?,?)",
                review, orgA, author, author);
        assertEquals(List.of(rev), app.queryForList("select r_user_id from notification_reviewers_of_review(?)", UUID.class, review));
        // there is no way to ask by organisation or exam: the only argument is a review id, and an unknown one yields nobody
        assertEquals(0, app.queryForList("select r_user_id from notification_reviewers_of_review(?)", UUID.class, orgA).size());
        assertEquals(0, app.queryForList("select r_user_id from notification_reviewers_of_review(?)", UUID.class, UUID.randomUUID()).size());
    }

    @Test
    void issuingALinkTokenNeedsASignedInPerson() {
        assertThrows(DataAccessException.class, () -> asApp(null, null, false,
                t -> t.queryForList("select 1 from telegram_link_issue(?, now(), now() + interval '1 hour')", Integer.class, new byte[32])));
        UUID u = user("dbIssue");
        asApp(u, null, false, t -> {
            t.queryForList("select telegram_link_issue(?, ?, ?)", new byte[] {1, 2, 3}, Timestamp.from(Instant.now()), Timestamp.from(Instant.now().plusSeconds(60)));
            return null;
        });
        assertEquals(1, su.queryForObject("select count(*) from telegram_link_tokens where user_id = ?", Integer.class, u));
    }
}
