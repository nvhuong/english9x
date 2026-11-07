package com.english9x.notification.api;

/**
 * Who a message goes to, as the sender needs it. {@code locale} is the recipient's own choice ("vi" or "en"). {@code email} is set for
 * EMAIL deliveries only and {@code chatId} for TELEGRAM only. Never logged.
 */
public record Recipient(String displayName, String locale, String email, Long chatId) {

    @Override
    public String toString() {
        return "Recipient[]";
    }
}
