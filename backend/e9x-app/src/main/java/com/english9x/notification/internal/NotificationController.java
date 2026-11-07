package com.english9x.notification.internal;

import com.english9x.notification.api.Channel;
import com.english9x.notification.api.EventType;
import com.english9x.notification.internal.InboxService.Page;
import com.english9x.notification.internal.InboxService.Prefs;
import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import java.time.Instant;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

/**
 * The person's notifications (F52), preferences by event and channel (F55) and Telegram link (F54). All of it is about the signed-in
 * person, whatever organisation context the request names; nothing takes a user id.
 */
@RestController
class NotificationController {

    @Schema(name = "NotificationUnreadCount")
    record UnreadCount(int count) {}

    @Schema(name = "NotificationPrefRequest")
    record PrefRequest(@NotBlank String eventType, @NotBlank String channel, @NotNull Boolean enabled) {}

    record TelegramStatus(boolean enabled, boolean linked) {}

    record TelegramLink(String deepLink, Instant expiresAt) {}

    private final InboxService inbox;
    private final TelegramLinkService telegram;

    NotificationController(InboxService inbox, TelegramLinkService telegram) {
        this.inbox = inbox;
        this.telegram = telegram;
    }

    @GetMapping("/api/me/notifications")
    Page list(@RequestParam(defaultValue = "20") int limit, @RequestParam(defaultValue = "0") int offset) {
        if (limit < 1 || limit > 100) {
            throw NotificationProblems.validation("limit", "Range", "limit must be between 1 and 100");
        }
        if (offset < 0) {
            throw NotificationProblems.validation("offset", "Min", "offset must not be negative");
        }
        return inbox.list(limit, offset);
    }

    @GetMapping("/api/me/notifications/unread-count")
    UnreadCount unread() {
        return new UnreadCount(inbox.unread());
    }

    @PostMapping("/api/me/notifications/{id}/read")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    void markRead(@PathVariable UUID id) {
        if (!inbox.markRead(id)) {
            throw NotificationProblems.notFound();
        }
    }

    @PostMapping("/api/me/notifications/read-all")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    void markAllRead() {
        inbox.markAllRead();
    }

    @GetMapping("/api/me/notification-prefs")
    Prefs prefs() {
        return inbox.prefs();
    }

    @PutMapping("/api/me/notification-prefs")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    void setPref(@Valid @RequestBody PrefRequest body) {
        EventType type;
        try {
            type = EventType.valueOf(body.eventType());
        } catch (IllegalArgumentException e) {
            throw NotificationProblems.validation("eventType", "Unknown", "unknown event type");
        }
        Channel channel;
        try {
            channel = Channel.valueOf(body.channel());
        } catch (IllegalArgumentException e) {
            throw NotificationProblems.validation("channel", "Unknown", "unknown channel");
        }
        if (!channel.optional()) {
            throw NotificationProblems.validation("channel", "AlwaysOn", "in-app notifications are always on");
        }
        inbox.setPref(type, channel, body.enabled());
    }

    @GetMapping("/api/me/telegram")
    TelegramStatus telegram() {
        return new TelegramStatus(telegram.enabled(), telegram.linked());
    }

    /** A one-time deep link: opening it in Telegram and pressing Start sends {@code /start <token>} to the bot. */
    @PostMapping("/api/me/telegram/link")
    TelegramLink link() {
        TelegramLinkService.Issued issued = telegram.issue();
        return new TelegramLink(issued.deepLink(), issued.expiresAt());
    }

    @DeleteMapping("/api/me/telegram")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    void unlink() {
        telegram.unlink();
    }
}
