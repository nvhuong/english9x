package com.english9x.notification;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.english9x.support.FakeTelegram;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.awaitility.Awaitility;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;
import org.springframework.http.HttpMethod;

/** Linking a Telegram chat: the one-time token, the unauthenticated webhook and its secret, and the rule for a chat already linked. */
@ExtendWith(OutputCaptureExtension.class)
class TelegramLinkIT extends NotificationIT {

    static final String SECRET_HEADER = "X-Telegram-Bot-Api-Secret-Token";

    private String linkToken(UUID user) {
        Res r = post("/api/me/telegram/link", null, user);
        assertEquals(200, r.status(), r.raw());
        String deep = r.body().path("deepLink").asText();
        assertTrue(deep.startsWith("https://t.me/" + BOT_USERNAME + "?start="), deep);
        return deep.substring(deep.indexOf("start=") + 6);
    }

    private Res webhook(String secret, Object update) {
        Map<String, String> h = secret == null ? Map.of() : Map.of(SECRET_HEADER, secret);
        return call(HttpMethod.POST, "/api/telegram/webhook", update, (String) null, h);
    }

    private Map<String, Object> start(String chat, String token) {
        return Map.of("update_id", 1, "message", Map.of("message_id", 1, "chat", Map.of("id", Long.parseLong(chat), "type", "private"),
                "text", "/start " + token));
    }

    private String chatOf(UUID user) {
        List<String> r = su.queryForList("select chat_id::text from telegram_links where user_id = ?", String.class, user);
        return r.isEmpty() ? null : r.get(0);
    }

    @Test
    void tokenSingleUse() {
        UUID u = user("link");
        String chat = newChatId();
        String token = linkToken(u);
        assertEquals(200, webhook(WEBHOOK_SECRET, start(chat, token)).status());
        assertEquals(chat, chatOf(u));
        Awaitility.await().atMost(Duration.ofSeconds(10)).until(() -> !TELEGRAM.to(chat).isEmpty());
        assertTrue(TELEGRAM.to(chat).get(0).text().contains("Đã liên kết Telegram"), "the bot confirms in the person's language");

        // the same token from another chat (a replay) links nothing and gets the generic refusal
        String other = newChatId();
        assertEquals(200, webhook(WEBHOOK_SECRET, start(other, token)).status());
        assertEquals(chat, chatOf(u), "still the first chat");
        assertEquals(0, su.queryForObject("select count(*) from telegram_links where chat_id = ?::bigint", Integer.class, other));
        Awaitility.await().atMost(Duration.ofSeconds(10)).until(() -> !TELEGRAM.to(other).isEmpty());
        assertTrue(TELEGRAM.to(other).get(0).text().contains("không hợp lệ hoặc đã hết hạn"));
    }

    @Test
    void theTokenIsHighEntropyStoredAsADigestAndExpires() throws Exception {
        UUID u = user("digest");
        String first = linkToken(u);
        assertTrue(first.matches("[A-Za-z0-9_-]{43}"), "256 bits, URL-safe: " + first);
        String newest = linkToken(u);
        assertNotEquals(first, newest, "every request is a new token");
        assertEquals(1, su.queryForObject("select count(*) from telegram_link_tokens where user_id = ?", Integer.class, u),
                "the older unused token was retired");
        byte[] stored = su.queryForObject("select token_hash from telegram_link_tokens where user_id = ?", byte[].class, u);
        assertArrayEquals(MessageDigest.getInstance("SHA-256").digest(newest.getBytes(StandardCharsets.UTF_8)), stored, "only the SHA-256 digest is kept");
        assertEquals(0, su.queryForObject("select count(*) from telegram_link_tokens where token_hash = ?", Integer.class, (Object) newest.getBytes(StandardCharsets.UTF_8)),
                "the raw token is not what is stored");

        su.update("update telegram_link_tokens set expires_at = now() - interval '1 second' where user_id = ?", u);
        String chat = newChatId();
        assertEquals(200, webhook(WEBHOOK_SECRET, start(chat, newest)).status());
        assertEquals(null, chatOf(u), "an expired token links nothing");
    }

    @Test
    void theWebhookNeedsTheSecretHeaderAndRefusesEverythingElse() {
        UUID u = user("secret");
        String chat = newChatId();
        String token = linkToken(u);
        assertEquals(401, webhook(null, start(chat, token)).status(), "no header");
        assertEquals(401, webhook("wrong", start(chat, token)).status(), "wrong secret");
        assertEquals(401, webhook(WEBHOOK_SECRET + "x", start(chat, token)).status(), "almost right");
        assertEquals(401, webhook("", start(chat, token)).status(), "empty");
        assertEquals(null, chatOf(u), "nothing linked");
        assertEquals(200, webhook(WEBHOOK_SECRET, start(chat, token)).status(), "the token was not spent by the refused calls");
        assertEquals(chat, chatOf(u));
    }

    @Test
    void aWrongAndAnUnknownTokenAreAnsweredAlike() {
        String c1 = newChatId();
        String c2 = newChatId();
        Res a = webhook(WEBHOOK_SECRET, start(c1, "A".repeat(43)));
        Res b = webhook(WEBHOOK_SECRET, start(c2, "not a token at all!"));
        assertEquals(a.status(), b.status());
        assertEquals(a.raw(), b.raw());
        Awaitility.await().atMost(Duration.ofSeconds(10)).until(() -> !TELEGRAM.to(c1).isEmpty() && !TELEGRAM.to(c2).isEmpty());
        assertEquals(TELEGRAM.to(c1).get(0).text(), TELEGRAM.to(c2).get(0).text());
    }

    @Test
    void aChatLinkedToAnotherAccountIsNotStolen() {
        UUID owner = user("owner");
        UUID other = user("thief");
        String chat = newChatId();
        assertEquals(200, webhook(WEBHOOK_SECRET, start(chat, linkToken(owner))).status());
        String token = linkToken(other);
        assertEquals(200, webhook(WEBHOOK_SECRET, start(chat, token)).status());
        assertEquals(chat, chatOf(owner));
        assertEquals(null, chatOf(other), "the rule: a chat belongs to one account; unlink there first");
        Awaitility.await().atMost(Duration.ofSeconds(10)).until(() -> TELEGRAM.to(chat).size() >= 2);
        assertTrue(TELEGRAM.to(chat).get(1).text().contains("đã được liên kết với một tài khoản English9x khác"));

        // once the owner unlinks, the same (still unused) token works
        assertEquals(204, call(HttpMethod.DELETE, "/api/me/telegram", null, owner, null).status());
        assertEquals(null, chatOf(owner));
        assertEquals(200, webhook(WEBHOOK_SECRET, start(chat, token)).status());
        assertEquals(chat, chatOf(other));
    }

    @Test
    void linkingAnotherChatReplacesTheOldOneOfTheSameAccount() {
        UUID u = user("replace");
        String c1 = newChatId();
        String c2 = newChatId();
        webhook(WEBHOOK_SECRET, start(c1, linkToken(u)));
        webhook(WEBHOOK_SECRET, start(c2, linkToken(u)));
        assertEquals(c2, chatOf(u));
        assertEquals(1, su.queryForObject("select count(*) from telegram_links where user_id = ?", Integer.class, u));
    }

    @Test
    void onlyPrivateChatsAndStartMessagesLinkAnything() {
        UUID u = user("group");
        String token = linkToken(u);
        String chat = newChatId();
        Map<String, Object> group = Map.of("update_id", 2, "message", Map.of("chat", Map.of("id", Long.parseLong(chat), "type", "group"), "text", "/start " + token));
        assertEquals(200, webhook(WEBHOOK_SECRET, group).status());
        assertEquals(null, chatOf(u), "a group chat is never linked");
        assertEquals(200, webhook(WEBHOOK_SECRET, Map.of("update_id", 3, "edited_message", Map.of())).status());
        assertEquals(200, webhook(WEBHOOK_SECRET, "not json at all").status(), "garbage from a holder of the secret is still just acknowledged");
    }

    @Test
    void statusUnlinkAndPrivacyOfTheLink() {
        UUID u = user("status");
        assertEquals(false, get("/api/me/telegram", u).body().path("linked").asBoolean());
        assertEquals(true, get("/api/me/telegram", u).body().path("enabled").asBoolean());
        webhook(WEBHOOK_SECRET, start(newChatId(), linkToken(u)));
        assertEquals(true, get("/api/me/telegram", u).body().path("linked").asBoolean());
        assertFalse(get("/api/me/telegram", u).raw().contains("chat"), "the chat id is not exposed");
        assertEquals(204, call(HttpMethod.DELETE, "/api/me/telegram", null, u, null).status());
        assertEquals(false, get("/api/me/telegram", u).body().path("linked").asBoolean());
        assertEquals(401, call(HttpMethod.POST, "/api/me/telegram/link", null, (UUID) null, null).status());
    }

    @Test
    void theBotTokenAndTheTokensNeverAppearInTheLogs(CapturedOutput out) {
        UUID u = user("logs");
        String token = linkToken(u);
        webhook(WEBHOOK_SECRET, start(newChatId(), token));
        webhook("wrong-secret", start(newChatId(), token));
        TELEGRAM.failWith(500);
        try {
            webhook(WEBHOOK_SECRET, start(newChatId(), "B".repeat(43)));
            Awaitility.await().pollDelay(Duration.ofMillis(500)).atMost(Duration.ofSeconds(5)).until(() -> true);
        } finally {
            TELEGRAM.failWith(0);
        }
        assertFalse(out.getAll().contains(FakeTelegram.BOT_TOKEN));
        assertFalse(out.getAll().contains(token));
        assertFalse(out.getAll().contains(WEBHOOK_SECRET));
    }
}
