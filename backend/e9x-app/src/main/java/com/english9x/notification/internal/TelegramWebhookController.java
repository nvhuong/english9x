package com.english9x.notification.internal;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.servlet.http.HttpServletRequest;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RestController;

/**
 * {@code POST /api/telegram/webhook}: Telegram's updates. The route is public (Telegram has no access token), so the ONLY thing that
 * lets a call in is the secret token Telegram echoes in {@code X-Telegram-Bot-Api-Secret-Token} (set with setWebhook's
 * {@code secret_token}). It is compared in constant time (digests of equal length, {@link MessageDigest#isEqual}); a missing, wrong or
 * unconfigured secret is a 401 and the body is not even READ (the request is taken as a servlet request, so nothing is buffered before
 * the check; Caddy also caps the body of this route, see infra/Caddyfile). Past the check at most {@value #MAX_BODY} bytes are read.
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
    private static final Pattern BARE_START = Pattern.compile("^/start(?:@\\w+)?\\s*$");
    /** A Telegram update is a few KB; anything bigger than this is not one. */
    static final int MAX_BODY = 64 * 1024;

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
    Map<String, Object> receive(@RequestHeader(name = SECRET_HEADER, required = false) String secret, HttpServletRequest request) {
        if (!secretMatches(secret)) {
            throw NotificationProblems.webhookUnauthorized();
        }
        JsonNode update;
        try {
            byte[] body = request.getInputStream().readNBytes(MAX_BODY + 1);
            update = body.length == 0 || body.length > MAX_BODY ? null : mapper.readTree(body);
        } catch (IOException | RuntimeException e) {
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
        long chatId = chat.path("id").asLong();
        String text = message.path("text").asText("");
        if (BARE_START.matcher(text).matches()) {
            reply(chatId, templates.text("telegram.start-help", "vi"));
            return Map.of();
        }
        Matcher m = START.matcher(text);
        if (!m.matches()) {
            return Map.of();
        }
        TelegramLinkService.Consumed result = links.consume(m.group(1).strip(), chatId);
        String answer = switch (result.status()) {
            case "LINKED" -> templates.text("telegram.linked", result.locale());
            case "CHAT_TAKEN" -> templates.text("telegram.taken", "vi");
            default -> templates.text("telegram.invalid", "vi");
        };
        reply(chatId, answer);
        if (result.oldChat() != null) {
            // best effort and off this thread: the person's new chat must not wait for a chat that may be slow or gone
            String notice = templates.text("telegram.replaced", result.locale());
            long oldChat = result.oldChat();
            Thread.ofVirtual().name("telegram-replaced-notice").start(() -> reply(oldChat, notice));
        }
        return Map.of();
    }

    private void reply(long chatId, String text) {
        try {
            client.sendMessage(chatId, text, false);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        } catch (Exception e) {
            // the class only: the message of an HTTP failure may hold the URL, which holds the bot token
            LOG.warn("telegram reply could not be sent: {}", DeliveryFailures.reason(e));
        }
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
