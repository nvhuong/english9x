package com.english9x.notification.internal;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RestController;

/**
 * {@code POST /api/telegram/webhook}: Telegram's updates. The route is public (Telegram has no access token), so the ONLY thing that
 * lets a call in is the secret token Telegram echoes in {@code X-Telegram-Bot-Api-Secret-Token} (set with setWebhook's
 * {@code secret_token}). It is compared in constant time (digests of equal length, {@link MessageDigest#isEqual}); a missing, wrong or
 * unconfigured secret is a 401 and the body is not even parsed.
 *
 * <p>Past that gate the answer is always {@code 200 {}}: Telegram only needs an acknowledgement, and what the bot says back to the CHAT
 * (never to the HTTP caller) is the same for a wrong, an unknown, a used and an expired token, so nothing tells which tokens exist.
 * Only {@code /start <token>} sent in a private chat does anything. The bot token and the tokens are never logged.
 */
@RestController
class TelegramWebhookController {

    static final String SECRET_HEADER = "X-Telegram-Bot-Api-Secret-Token";
    private static final Logger LOG = LoggerFactory.getLogger(TelegramWebhookController.class);
    private static final Pattern START = Pattern.compile("^/start(?:@\\w+)?\\s+(.{1,200})$");

    private final NotificationProperties.Telegram cfg;
    private final TelegramLinkService links;
    private final TelegramClient client;
    private final Templates templates;
    private final ObjectMapper mapper;

    TelegramWebhookController(NotificationProperties props, TelegramLinkService links, TelegramClient client, Templates templates,
                              ObjectMapper mapper) {
        this.cfg = props.telegram();
        this.links = links;
        this.client = client;
        this.templates = templates;
        this.mapper = mapper;
    }

    @PostMapping("/api/telegram/webhook")
    Map<String, Object> receive(@RequestHeader(name = SECRET_HEADER, required = false) String secret,
                                @RequestBody(required = false) String body) {
        if (!secretMatches(secret)) {
            throw NotificationProblems.webhookUnauthorized();
        }
        JsonNode update;
        try {
            update = body == null ? null : mapper.readTree(body);
        } catch (Exception e) {
            return Map.of();
        }
        if (update == null) {
            return Map.of();
        }
        JsonNode message = update.path("message");
        JsonNode chat = message.path("chat");
        if (!"private".equals(chat.path("type").asText()) || !chat.path("id").canConvertToLong()) {
            return Map.of();
        }
        Matcher m = START.matcher(message.path("text").asText(""));
        if (!m.matches()) {
            return Map.of();
        }
        long chatId = chat.path("id").asLong();
        TelegramLinkService.Consumed result = links.consume(m.group(1).strip(), chatId);
        String reply = switch (result.status()) {
            case "LINKED" -> templates.text("telegram.linked", result.locale());
            case "CHAT_TAKEN" -> templates.text("telegram.taken", "vi");
            default -> templates.text("telegram.invalid", "vi");
        };
        try {
            client.sendMessage(chatId, reply, false);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        } catch (Exception e) {
            // the class only: the message of an HTTP failure may hold the URL, which holds the bot token
            LOG.warn("telegram reply could not be sent: {}", DeliveryFailures.reason(e));
        }
        return Map.of();
    }

    private boolean secretMatches(String given) {
        String expected = cfg.webhookSecret();
        if (expected == null || expected.isBlank() || given == null) {
            return false;
        }
        return MessageDigest.isEqual(sha256(expected), sha256(given));
    }

    private static byte[] sha256(String s) {
        try {
            return MessageDigest.getInstance("SHA-256").digest(s.getBytes(StandardCharsets.UTF_8));
        } catch (java.security.NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }
}
