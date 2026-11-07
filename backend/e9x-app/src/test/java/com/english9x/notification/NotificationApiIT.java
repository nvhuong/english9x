package com.english9x.notification;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.english9x.notification.api.EventType;
import com.english9x.notification.api.NotificationService;
import com.fasterxml.jackson.databind.JsonNode;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpMethod;

/** The person's inbox and preferences over HTTP: private to the person, the same in every context, marking read, per event and channel. */
class NotificationApiIT extends NotificationIT {

    @Autowired NotificationService notifications;

    private void make(UUID u, EventType type, String key) {
        notifications.notify(type, u, Map.of("examCode", "IELTS", "contentType", "question"), key);
    }

    private List<String> ids(Res r) {
        List<String> out = new ArrayList<>();
        for (JsonNode n : r.body().path("items")) {
            out.add(n.path("id").asText());
        }
        return out;
    }

    @Test
    void listsOwnNotificationsNewestFirstWithPagingAndAnUnreadCount() {
        UUID u = user("inbox");
        for (int i = 0; i < 5; i++) {
            make(u, EventType.REVIEW_REQUESTED, "k" + i);
        }
        su.update("update notifications set created_at = now() - (interval '1 minute' * (extract(epoch from now() - created_at) * 0)) - interval '1 second' * (random() * 0) where user_id = ?", u);
        Res all = get("/api/me/notifications", u);
        assertEquals(200, all.status(), all.raw());
        assertEquals(5, all.body().path("items").size());
        assertEquals(5, all.body().path("unread").asInt());
        JsonNode first = all.body().path("items").get(0);
        assertEquals("REVIEW_REQUESTED", first.path("type").asText());
        assertEquals("IELTS", first.path("data").path("examCode").asText());
        assertFalse(first.path("read").asBoolean());
        assertTrue(first.has("createdAt"));
        Res page = get("/api/me/notifications?limit=2&offset=2", u);
        assertEquals(2, page.body().path("items").size());
        assertEquals(all.body().path("items").get(2).path("id").asText(), page.body().path("items").get(0).path("id").asText());
        assertEquals(400, get("/api/me/notifications?limit=0", u).status());
        assertEquals(400, get("/api/me/notifications?limit=1000", u).status());
    }

    @Test
    void markingReadWorksOnOwnRowsAndCountsDown() {
        UUID u = user("read");
        make(u, EventType.CONTENT_PUBLISHED, "a");
        make(u, EventType.CONTENT_PUBLISHED, "b");
        String id = ids(get("/api/me/notifications", u)).get(0);
        assertEquals(204, post("/api/me/notifications/" + id + "/read", null, u).status());
        assertEquals(204, post("/api/me/notifications/" + id + "/read", null, u).status(), "idempotent");
        Res after = get("/api/me/notifications", u);
        assertEquals(1, after.body().path("unread").asInt());
        assertEquals(1, get("/api/me/notifications/unread-count", u).body().path("count").asInt());
        assertEquals(204, post("/api/me/notifications/read-all", null, u).status());
        assertEquals(0, get("/api/me/notifications/unread-count", u).body().path("count").asInt());
        assertTrue(get("/api/me/notifications", u).body().path("items").get(0).path("read").asBoolean());
    }

    @Test
    void notificationsArePrivateToThePersonInEveryContext() {
        UUID a = user("privA");
        UUID b = user("privB");
        UUID orgOfB = org("CENTER");
        grant(b, orgOfB, null, "ORG_ADMIN");
        grant(b, null, "IELTS", "REVIEWER");
        make(a, EventType.REVIEW_REQUESTED, "mine");
        String idOfA = ids(get("/api/me/notifications", a)).get(0);

        // b, even an organisation admin and English9x staff, sees none of a's, in any context
        for (UUID ctx : new UUID[] {null, orgOfB}) {
            Res r = call(HttpMethod.GET, "/api/me/notifications", null, b, ctx);
            assertEquals(200, r.status(), r.raw());
            assertEquals(0, r.body().path("items").size());
            assertFalse(r.raw().contains(idOfA));
        }
        // marking another person's notification is a 404 exactly like a notification that does not exist
        Res foreign = post("/api/me/notifications/" + idOfA + "/read", null, b);
        Res none = post("/api/me/notifications/" + UUID.randomUUID() + "/read", null, b);
        assertEquals(404, foreign.status());
        assertEquals(none.status(), foreign.status());
        assertEquals(none.body().path("type").asText(), foreign.body().path("type").asText());
        assertEquals(0, su.queryForObject("select count(*) from notifications where id = ?::uuid and read_at is not null", Integer.class, idOfA));
        // read-all by b does not touch a's rows
        assertEquals(204, post("/api/me/notifications/read-all", null, b).status());
        assertEquals(1, get("/api/me/notifications/unread-count", a).body().path("count").asInt());
        // the same list whatever the context of a's own request
        make(a, EventType.REVIEW_REQUESTED, "second");
        UUID orgOfA = org("CENTER");
        grant(a, orgOfA, "IELTS", "AUTHOR");
        assertEquals(ids(get("/api/me/notifications", a)), ids(call(HttpMethod.GET, "/api/me/notifications", null, a, orgOfA)));
    }

    @Test
    void everyEndpointNeedsASignedInPerson() {
        for (String path : new String[] {"/api/me/notifications", "/api/me/notifications/unread-count", "/api/me/notification-prefs", "/api/me/telegram"}) {
            assertEquals(401, call(HttpMethod.GET, path, null, (UUID) null, null).status(), path);
        }
        assertEquals(401, call(HttpMethod.POST, "/api/me/notifications/read-all", null, (UUID) null, null).status());
        assertEquals(401, call(HttpMethod.PUT, "/api/me/notification-prefs", Map.of(), (UUID) null, null).status());
    }

    @Test
    void preferencesPerEventAndChannelTakeEffect() {
        UUID u = user("prefs");
        Res initial = get("/api/me/notification-prefs", u);
        assertEquals(200, initial.status(), initial.raw());
        assertEquals(EventType.values().length, initial.body().path("events").size());
        for (JsonNode e : initial.body().path("events")) {
            assertTrue(e.path("email").asBoolean() && e.path("telegram").asBoolean(), "everything is on until the person says otherwise: " + e);
        }
        assertEquals(204, call(HttpMethod.PUT, "/api/me/notification-prefs", Map.of("eventType", "REVIEW_REQUESTED", "channel", "EMAIL", "enabled", false), u, null).status());
        assertEquals(204, call(HttpMethod.PUT, "/api/me/notification-prefs", Map.of("eventType", "REVIEW_REQUESTED", "channel", "EMAIL", "enabled", false), u, null).status(), "repeatable");
        Res after = get("/api/me/notification-prefs", u);
        for (JsonNode e : after.body().path("events")) {
            boolean target = "REVIEW_REQUESTED".equals(e.path("type").asText());
            assertEquals(!target, e.path("email").asBoolean(), e.toString());
            assertTrue(e.path("telegram").asBoolean());
        }
        assertEquals(0, notifications.notify(EventType.REVIEW_REQUESTED, u, Map.of("examCode", "IELTS"), "pp1"), "email is off, Telegram is not linked");
        assertEquals(204, call(HttpMethod.PUT, "/api/me/notification-prefs", Map.of("eventType", "REVIEW_REQUESTED", "channel", "EMAIL", "enabled", true), u, null).status());
        assertEquals(1, notifications.notify(EventType.REVIEW_REQUESTED, u, Map.of("examCode", "IELTS"), "pp2"));
    }

    @Test
    void inAppCannotBeSwitchedOffAndBadInputIsRefused() {
        UUID u = user("badprefs");
        assertEquals(400, call(HttpMethod.PUT, "/api/me/notification-prefs", Map.of("eventType", "REVIEW_REQUESTED", "channel", "IN_APP", "enabled", false), u, null).status());
        assertEquals(400, call(HttpMethod.PUT, "/api/me/notification-prefs", Map.of("eventType", "NOPE", "channel", "EMAIL", "enabled", false), u, null).status());
        assertEquals(400, call(HttpMethod.PUT, "/api/me/notification-prefs", Map.of("eventType", "REVIEW_REQUESTED", "channel", "SMS", "enabled", false), u, null).status());
        assertEquals(400, call(HttpMethod.PUT, "/api/me/notification-prefs", Map.of("channel", "EMAIL"), u, null).status());
        assertEquals(0, su.queryForObject("select count(*) from notification_prefs where user_id = ?", Integer.class, u));
    }

    @Test
    void theMessageCatalogueOfThePreferencesNamesTheChannels() {
        UUID u = user("channels");
        Res r = get("/api/me/notification-prefs", u);
        assertEquals(List.of("IN_APP", "EMAIL", "TELEGRAM"), JSON.convertValue(r.body().path("channels"), List.class));
        assertEquals("REVIEWER", r.body().path("events").findParents("type").stream()
                .filter(n -> "REVIEW_REQUESTED".equals(n.path("type").asText())).findFirst().orElseThrow().path("audience").asText());
    }
}
