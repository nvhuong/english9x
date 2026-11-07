package com.english9x.notification.internal;

import java.time.Duration;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

/**
 * {@code e9x.notification.*}. The bot token and the webhook secret have no default: with an empty token the Telegram channel is off
 * (nothing is linked or sent), and with an empty secret the webhook refuses every call.
 */
@ConfigurationProperties("e9x.notification")
record NotificationProperties(
        @DefaultValue("English9x <no-reply@english9x.vn>") String mailFrom,
        @DefaultValue Telegram telegram) {

    record Telegram(
            @DefaultValue("https://api.telegram.org") String apiBase,
            @DefaultValue("") String botToken,
            @DefaultValue("") String botUsername,
            @DefaultValue("") String webhookSecret,
            @DefaultValue("15m") Duration linkTtl) {

        boolean enabled() {
            return !botToken.isBlank() && !botUsername.isBlank();
        }
    }
}
