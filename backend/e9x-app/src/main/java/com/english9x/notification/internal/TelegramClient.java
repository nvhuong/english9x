package com.english9x.notification.internal;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import org.springframework.stereotype.Component;

/**
 * The Telegram Bot API as far as this module needs it: {@code sendMessage}. The base URL is configuration (the E2E stack points it at
 * the fake). The bot token is part of the request URL, so no exception, log line or problem body built here ever carries a URL or a
 * message of the HTTP stack: callers get {@link HttpDeliveryException} (status only), {@link PermanentDeliveryException} or an
 * {@link IOException} whose message they must not use.
 */
@Component
class TelegramClient {

    private final NotificationProperties.Telegram cfg;
    private final ObjectMapper mapper;
    private final HttpClient http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(5)).followRedirects(HttpClient.Redirect.NEVER).build();

    TelegramClient(NotificationProperties props, ObjectMapper mapper) {
        this.cfg = props.telegram();
        this.mapper = mapper;
    }

    boolean enabled() {
        return cfg.enabled();
    }

    /**
     * @param htmlText Telegram HTML (already escaped by the caller) when {@code html}, otherwise plain text
     * @throws PermanentDeliveryException the API refused for good (400, 401, 403, 404: a blocked bot, a bad chat, a wrong token)
     * @throws HttpDeliveryException any other failure status (429, 5xx ...): worth retrying
     */
    void sendMessage(long chatId, String htmlText, boolean html) throws IOException, InterruptedException, PermanentDeliveryException, HttpDeliveryException {
        if (!cfg.enabled()) {
            throw new PermanentDeliveryException("telegram-disabled");
        }
        ObjectNode body = mapper.createObjectNode();
        body.put("chat_id", chatId);
        body.put("text", htmlText);
        if (html) {
            body.put("parse_mode", "HTML");
        }
        body.put("disable_web_page_preview", true);
        HttpRequest request = HttpRequest.newBuilder(URI.create(cfg.apiBase().replaceAll("/+$", "") + "/bot" + cfg.botToken() + "/sendMessage"))
                .timeout(Duration.ofSeconds(10))
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(mapper.writeValueAsString(body)))
                .build();
        int status = http.send(request, HttpResponse.BodyHandlers.discarding()).statusCode();
        if (status / 100 == 2) {
            return;
        }
        if (status == 400 || status == 401 || status == 403 || status == 404) {
            throw new PermanentDeliveryException("http-" + status);
        }
        throw new HttpDeliveryException(status);
    }
}
