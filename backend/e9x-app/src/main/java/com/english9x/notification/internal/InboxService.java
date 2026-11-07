package com.english9x.notification.internal;

import com.english9x.notification.api.Channel;
import com.english9x.notification.api.EventType;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.swagger.v3.oas.annotations.media.Schema;
import java.sql.Timestamp;
import java.time.Clock;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * What a person sees and changes about their own notifications. Every statement runs as the signed-in person under Row-Level Security
 * (policies by user, not by context): the same answer in the personal context and in any organisation's.
 */
@Service
class InboxService {

    @Schema(name = "NotificationItem")
    record Item(UUID id, String type, JsonNode data, Instant createdAt, boolean read) {}

    @Schema(name = "NotificationPage")
    record Page(List<Item> items, int unread) {}

    @Schema(name = "NotificationEventPrefs")
    record EventPrefs(String type, String audience, boolean email, boolean telegram) {}

    @Schema(name = "NotificationPrefs")
    record Prefs(List<String> channels, List<EventPrefs> events) {}

    private final JdbcTemplate jdbc;
    private final ObjectMapper mapper;
    private final Clock clock;

    InboxService(JdbcTemplate jdbc, ObjectMapper mapper, Clock clock) {
        this.jdbc = jdbc;
        this.mapper = mapper;
        this.clock = clock;
    }

    @Transactional(readOnly = true)
    Page list(int limit, int offset) {
        List<Item> items = jdbc.query("select id, event_type, data::text, created_at, read_at from notifications "
                + "order by created_at desc, id desc limit ? offset ?", (rs, i) -> {
                    try {
                        return new Item(rs.getObject(1, UUID.class), rs.getString(2), mapper.readTree(rs.getString(3)),
                                rs.getTimestamp(4).toInstant(), rs.getTimestamp(5) != null);
                    } catch (Exception e) {
                        throw new IllegalStateException("unreadable notification data");
                    }
                }, limit, offset);
        return new Page(items, unread());
    }

    @Transactional(readOnly = true)
    int unread() {
        return jdbc.queryForObject("select count(*) from notifications where read_at is null and user_id = e9x_current_user()", Integer.class);
    }

    /** Idempotent; false for a notification that is not the caller's or does not exist (indistinguishable). */
    @Transactional
    boolean markRead(UUID id) {
        return jdbc.update("update notifications set read_at = coalesce(read_at, ?) where id = ? and user_id = e9x_current_user()", Timestamp.from(clock.instant()), id) == 1;
    }

    @Transactional
    void markAllRead() {
        jdbc.update("update notifications set read_at = ? where read_at is null and user_id = e9x_current_user()", Timestamp.from(clock.instant()));
    }

    @Transactional(readOnly = true)
    Prefs prefs() {
        Map<String, Boolean> set = new HashMap<>();
        jdbc.query("select event_type, channel, enabled from notification_prefs",
                rs -> {
                    set.put(rs.getString(1) + "/" + rs.getString(2), rs.getBoolean(3));
                });
        List<EventPrefs> events = new ArrayList<>();
        for (EventType t : EventType.values()) {
            events.add(new EventPrefs(t.name(), t.audience(), set.getOrDefault(t.name() + "/EMAIL", true), set.getOrDefault(t.name() + "/TELEGRAM", true)));
        }
        return new Prefs(List.of(Channel.IN_APP.name(), Channel.EMAIL.name(), Channel.TELEGRAM.name()), events);
    }

    @Transactional
    void setPref(EventType type, Channel channel, boolean enabled) {
        jdbc.update("insert into notification_prefs(user_id, event_type, channel, enabled, updated_at) values (e9x_current_user(), ?, ?, ?, ?) "
                + "on conflict (user_id, event_type, channel) do update set enabled = excluded.enabled, updated_at = excluded.updated_at",
                type.name(), channel.name(), enabled, Timestamp.from(clock.instant()));
    }
}
