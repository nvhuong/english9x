package com.english9x.notification;

import com.english9x.notification.api.EventType;
import com.english9x.support.FakeTelegram;
import com.english9x.support.TestAuth;
import com.english9x.support.TestPostgres;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.time.Duration;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicLong;
import org.awaitility.Awaitility;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

/**
 * Harness of the notification integration tests: one database and one application context for all subclasses, real worker threads
 * (polling every 200 ms), the SMTP sink of {@link TestAuth}, a fake Telegram Bot API, fixtures written as the superuser and requests
 * made with real signed access tokens. Tests use unique users instead of cleaning up.
 */
@SpringBootTest(
        webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
        properties = {
            "e9x.jobs.poll-interval=200ms",
            "e9x.jobs.send_notification.retry-delays=1s,1s",
            "e9x.events.redelivery-after=1s",
            "e9x.notification.telegram.link-ttl=15m"
        })
public abstract class NotificationIT {

    protected static final String DB = TestPostgres.createDatabase();
    protected static final ObjectMapper JSON = new ObjectMapper();
    protected static final FakeTelegram TELEGRAM = new FakeTelegram();
    protected static final String WEBHOOK_SECRET = "webhook-secret-for-tests-0123456789";
    protected static final String BOT_USERNAME = "e9x_test_bot";
    private static final AtomicLong CHATS = new AtomicLong(5_000_000_000L);

    @DynamicPropertySource
    static void properties(DynamicPropertyRegistry registry) {
        TestPostgres.connect(registry, DB);
        registry.add("e9x.notification.telegram.api-base", TELEGRAM::baseUrl);
        registry.add("e9x.notification.telegram.bot-token", () -> FakeTelegram.BOT_TOKEN);
        registry.add("e9x.notification.telegram.bot-username", () -> BOT_USERNAME);
        registry.add("e9x.notification.telegram.webhook-secret", () -> WEBHOOK_SECRET);
    }

    @Autowired protected TestRestTemplate http;

    /** Sees past Row-Level Security: fixtures and assertions only. */
    protected final JdbcTemplate su = new JdbcTemplate(TestPostgres.superuser(DB));

    protected record Res(int status, JsonNode body, HttpHeaders headers, String raw) {}

    // ---- fixtures ---------------------------------------------------------------------------------------------------------

    protected UUID user(String label) {
        return user(label, "vi", "User " + label);
    }

    protected UUID user(String label, String locale, String displayName) {
        UUID id = UUID.randomUUID();
        su.update("insert into users(id,email,display_name,email_verified_at,consent_at,locale) values (?,?,?,now(),now(),?)",
                id, label + "-" + id.toString().substring(0, 8) + "@example.test", displayName, locale);
        return id;
    }

    protected String emailOf(UUID user) {
        return su.queryForObject("select email from users where id = ?", String.class, user);
    }

    protected UUID org(String type) {
        UUID id = UUID.randomUUID();
        su.update("insert into organizations(id,name,type) values (?,?,?)", id, "Org " + id.toString().substring(0, 8), type);
        return id;
    }

    protected void grant(UUID user, UUID org, String exam, String role) {
        if (org != null) {
            su.update("insert into org_memberships(org_id,user_id) values (?,?) on conflict do nothing", org, user);
        }
        su.update("insert into role_grants(user_id,org_id,exam_code,role) values (?,?,?,?)", user, org, exam, role);
    }

    protected String newChatId() {
        return String.valueOf(CHATS.incrementAndGet());
    }

    /** Links a chat to the person the way the webhook would have (fixture, as the superuser). */
    protected String linkTelegram(UUID user) {
        String chat = newChatId();
        su.update("insert into telegram_links(user_id, chat_id) values (?, ?::bigint)", user, chat);
        return chat;
    }

    protected void setPref(UUID user, EventType type, String channel, boolean enabled) {
        su.update("insert into notification_prefs(user_id,event_type,channel,enabled) values (?,?,?,?) "
                + "on conflict (user_id,event_type,channel) do update set enabled = excluded.enabled", user, type.name(), channel, enabled);
    }

    protected List<Map<String, Object>> deliveries(UUID user) {
        return su.queryForList("select n.event_type, d.channel, d.status from notification_deliveries d join notifications n on n.id = d.notification_id "
                + "where n.user_id = ? order by n.created_at, d.channel", user);
    }

    protected int inAppCount(UUID user) {
        return su.queryForObject("select count(*) from notifications where user_id = ?", Integer.class, user);
    }

    protected void awaitDeliveryStatus(UUID user, String channel, String status) {
        Awaitility.await().atMost(Duration.ofSeconds(20)).pollInterval(Duration.ofMillis(100)).untilAsserted(() -> {
            List<Map<String, Object>> rows = deliveries(user);
            if (rows.stream().noneMatch(r -> channel.equals(r.get("channel")) && status.equals(r.get("status")))) {
                throw new AssertionError("no " + channel + " delivery " + status + " for " + user + ": " + rows);
            }
        });
    }

    // ---- requests ---------------------------------------------------------------------------------------------------------

    protected Res call(HttpMethod method, String path, Object body, UUID asUser, UUID inOrg) {
        Map<String, String> headers = new HashMap<>();
        if (inOrg != null) {
            headers.put("X-Org-Context", inOrg.toString());
        }
        return call(method, path, body, asUser == null ? null : TestAuth.accessToken(asUser), headers);
    }

    protected Res call(HttpMethod method, String path, Object body, String bearer, Map<String, String> headers) {
        HttpHeaders h = new HttpHeaders();
        h.setContentType(MediaType.APPLICATION_JSON);
        if (bearer != null) {
            h.setBearerAuth(bearer);
        }
        headers.forEach(h::add);
        try {
            String json = body == null ? null : (body instanceof String s ? s : JSON.writeValueAsString(body));
            ResponseEntity<String> r = http.exchange(path, method, new HttpEntity<>(json, h), String.class);
            String raw = r.getBody() == null ? "" : r.getBody();
            String trimmed = raw.trim();
            JsonNode node = trimmed.startsWith("{") || trimmed.startsWith("[") ? JSON.readTree(raw) : JSON.createObjectNode();
            return new Res(r.getStatusCode().value(), node, r.getHeaders(), raw);
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }

    protected Res get(String path, UUID asUser) {
        return call(HttpMethod.GET, path, null, asUser, null);
    }

    protected Res post(String path, Object body, UUID asUser) {
        return call(HttpMethod.POST, path, body, asUser, null);
    }
}
