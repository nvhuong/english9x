package com.english9x.notification;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.english9x.notification.api.EventType;
import com.english9x.notification.api.NotificationService;
import com.english9x.support.FakeTelegram;
import com.english9x.support.TestAuth;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.awaitility.Awaitility;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;

/**
 * NotificationService against the real database, the real job worker, the SMTP sink and the fake Telegram API: what it stores, which
 * channels it plans for, what the jobs carry, and what happens when a send fails.
 */
@ExtendWith(OutputCaptureExtension.class)
class NotificationServiceIT extends NotificationIT {

    @Autowired NotificationService notifications;

    private static Map<String, Object> reviewData() {
        return Map.of("contentType", "question", "examCode", "IELTS", "reviewId", UUID.randomUUID().toString());
    }

    @Test
    void respectsPrefsPerEventAndChannel() {
        UUID u = user("prefs");
        String chat = linkTelegram(u);
        String mail = emailOf(u);
        setPref(u, EventType.REVIEW_REQUESTED, "EMAIL", false);
        setPref(u, EventType.CONTENT_PUBLISHED, "TELEGRAM", false);

        assertEquals(1, notifications.notify(EventType.REVIEW_REQUESTED, u, reviewData(), "t1:" + u), "only Telegram is on for this event");
        assertEquals(1, notifications.notify(EventType.CONTENT_PUBLISHED, u, reviewData(), "t2:" + u), "only email is on for this event");
        assertEquals(2, notifications.notify(EventType.REVIEW_CHANGES_REQUESTED, u, reviewData(), "t3:" + u), "no preference: both are on");

        assertEquals(3, inAppCount(u), "in-app is always on");
        List<Map<String, Object>> rows = deliveries(u);
        assertEquals(4, rows.size(), rows.toString());
        assertTrue(rows.stream().noneMatch(r -> "REVIEW_REQUESTED".equals(r.get("event_type")) && "EMAIL".equals(r.get("channel"))));
        assertTrue(rows.stream().noneMatch(r -> "CONTENT_PUBLISHED".equals(r.get("event_type")) && "TELEGRAM".equals(r.get("channel"))));

        awaitDeliveryStatus(u, "TELEGRAM", "SENT");
        Awaitility.await().atMost(Duration.ofSeconds(20)).untilAsserted(() -> {
            assertEquals(2, TELEGRAM.to(chat).size(), "two Telegram messages");
            assertEquals(2, TestAuth.SMTP.to(mail).size(), "two emails");
        });
        assertTrue(deliveries(u).stream().allMatch(r -> "SENT".equals(r.get("status"))), deliveries(u).toString());
    }

    @Test
    void inAppIsAlwaysOnEvenWhenEveryOptionalChannelIsOff() {
        UUID u = user("inapp");
        linkTelegram(u);
        setPref(u, EventType.SCORE_OFFICIAL, "EMAIL", false);
        setPref(u, EventType.SCORE_OFFICIAL, "TELEGRAM", false);
        assertEquals(0, notifications.notify(EventType.SCORE_OFFICIAL, u, Map.of("attemptId", UUID.randomUUID().toString()), "k"));
        assertEquals(1, inAppCount(u));
        assertEquals(0, deliveries(u).size());
    }

    @Test
    void aRepeatedDedupeKeyNotifiesOnce() {
        UUID u = user("dedupe");
        String mail = emailOf(u);
        assertEquals(1, notifications.notify(EventType.REVIEW_REQUESTED, u, reviewData(), "same"));
        assertEquals(0, notifications.notify(EventType.REVIEW_REQUESTED, u, reviewData(), "same"), "redelivery of the event does nothing");
        assertEquals(1, inAppCount(u));
        TestAuth.SMTP.await(mail);
        // a second mail could only come from a second job; there is exactly one delivery row and it is SENT, so the one job finished
        awaitDeliveryStatus(u, "EMAIL", "SENT");
        assertEquals(1, deliveries(u).size(), "one delivery for the one notification");
        assertEquals(1, TestAuth.SMTP.to(mail).size());
        assertEquals(1, notifications.notify(EventType.REVIEW_REQUESTED, u, reviewData(), "other"), "another source event is another notification");
    }

    @Test
    void aUserWhoDoesNotExistIsIgnored() {
        assertEquals(0, notifications.notify(EventType.REVIEW_REQUESTED, UUID.randomUUID(), reviewData(), "ghost"));
    }

    @Test
    void theJobCarriesNoPersonalDataAndNoText() {
        UUID u = user("payload", "vi", "Nguyễn Văn Kín");
        String mail = emailOf(u);
        linkTelegram(u);
        notifications.notify(EventType.REVIEW_REQUESTED, u, reviewData(), "p");
        awaitDeliveryStatus(u, "EMAIL", "SENT");
        List<String> payloads = su.queryForList("select payload::text from jobs where type = 'send_notification' "
                + "and payload ->> 'notificationId' in (select id::text from notifications where user_id = ?)", String.class, u);
        assertEquals(2, payloads.size());
        for (String p : payloads) {
            assertFalse(p.contains(mail) || p.contains("Kín") || p.contains("IELTS"), p);
            assertEquals(2, JSON_KEYS(p), p);
        }
    }

    private static int JSON_KEYS(String json) {
        try {
            return JSON.readTree(json).size();
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }

    @Test
    void aFailingSendRetriesThroughTheQueueThenIsRecordedAndTheInAppRowSurvives(CapturedOutput out) {
        UUID u = user("fail");
        String chat = linkTelegram(u);
        setPref(u, EventType.REVIEW_REQUESTED, "EMAIL", false);
        TELEGRAM.failWith(500);
        try {
            assertEquals(1, notifications.notify(EventType.REVIEW_REQUESTED, u, reviewData(), "f"));
            awaitDeliveryStatus(u, "TELEGRAM", "FAILED");
        } finally {
            TELEGRAM.failWith(0);
        }
        assertEquals(3, TELEGRAM.to(chat).size(), "first try plus the two configured retries");
        assertEquals(1, inAppCount(u), "the in-app notification is not lost");
        String job = su.queryForObject("select status || ':' || attempts from jobs where payload ->> 'notificationId' = "
                + "(select id::text from notifications where user_id = ?) and payload ->> 'channel' = 'TELEGRAM'", String.class, u);
        assertEquals("FAILED:3", job);
        assertEquals("http-500", su.queryForObject("select last_error from notification_deliveries d join notifications n on n.id = d.notification_id "
                + "where n.user_id = ? and d.channel = 'TELEGRAM'", String.class, u));
        assertFalse(out.getAll().contains(FakeTelegram.BOT_TOKEN), "the bot token never reaches a log");
        assertFalse(out.getAll().contains("test-bot-token"), "not even a part of it");
    }

    @Test
    void aTelegramChatThatBlockedTheBotIsNotRetried() {
        UUID u = user("blocked");
        String chat = linkTelegram(u);
        setPref(u, EventType.REVIEW_REQUESTED, "EMAIL", false);
        TELEGRAM.failWith(403);
        try {
            notifications.notify(EventType.REVIEW_REQUESTED, u, reviewData(), "b");
            awaitDeliveryStatus(u, "TELEGRAM", "FAILED");
        } finally {
            TELEGRAM.failWith(0);
        }
        assertEquals(1, TELEGRAM.to(chat).size(), "a permanent refusal is not retried");
    }

    @Test
    void anUnmappableDataValueIsRefused() {
        UUID u = user("bad");
        assertThrows(IllegalArgumentException.class,
                () -> notifications.notify(EventType.REVIEW_REQUESTED, u, Map.of("nested", Map.of("a", 1)), "x"));
        assertThrows(IllegalArgumentException.class,
                () -> notifications.notify(EventType.REVIEW_REQUESTED, u, Map.of("long", "x".repeat(501)), "y"));
        assertEquals(0, inAppCount(u));
    }
}
