package com.english9x.notification.internal;

import com.english9x.notification.api.EventType;
import com.english9x.notification.api.NotificationService;
import com.english9x.platform.jobs.api.JobQueue;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.sql.Timestamp;
import java.time.Clock;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.regex.Pattern;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Stores the in-app notification and plans the optional deliveries in the database function {@code notification_create} (one statement
 * block: row, deliveries, idempotent by dedupe key), then enqueues one {@code send_notification} job per delivery in the same
 * transaction. The job payload is {@code {notificationId, channel}} only: no address, no name, no text. Nothing is sent here.
 */
@Service
class NotificationServiceImpl implements NotificationService {

    static final String JOB_TYPE = "send_notification";
    static final int MAX_KEYS = 20;
    static final int MAX_VALUE = 500;
    private static final Pattern KEY = Pattern.compile("[A-Za-z][A-Za-z0-9_]{0,39}");

    private final JdbcTemplate jdbc;
    private final JobQueue jobs;
    private final ObjectMapper mapper;
    private final Clock clock;

    NotificationServiceImpl(JdbcTemplate jdbc, JobQueue jobs, ObjectMapper mapper, Clock clock) {
        this.jdbc = jdbc;
        this.jobs = jobs;
        this.mapper = mapper;
        this.clock = clock;
    }

    @Override
    @Transactional
    public int notify(EventType type, UUID userId, Map<String, Object> data, String dedupeKey) {
        if (type == null || userId == null || dedupeKey == null || dedupeKey.isBlank() || dedupeKey.length() > 200) {
            throw new IllegalArgumentException("type, user and a dedupe key of 1..200 characters are required");
        }
        String json = json(data);
        record Planned(UUID id, String channel) {}
        List<Planned> planned = jdbc.query("select r_notification_id, r_channel from notification_create(?, ?, ?::jsonb, ?, ?)",
                (rs, i) -> new Planned(rs.getObject(1, UUID.class), rs.getString(2)),
                userId, type.name(), json, dedupeKey, Timestamp.from(clock.instant()));
        for (Planned p : planned) {
            ObjectNode payload = mapper.createObjectNode();
            payload.put("notificationId", p.id().toString());
            payload.put("channel", p.channel());
            jobs.enqueue(JOB_TYPE, payload, null);
        }
        return planned.size();
    }

    /** Scalars only, bounded: the data is shown to the person and stored with the notification. */
    private String json(Map<String, Object> data) {
        Map<String, Object> clean = new LinkedHashMap<>();
        if (data != null) {
            if (data.size() > MAX_KEYS) {
                throw new IllegalArgumentException("a notification carries at most " + MAX_KEYS + " data values");
            }
            for (Map.Entry<String, Object> e : data.entrySet()) {
                if (!KEY.matcher(e.getKey()).matches()) {
                    throw new IllegalArgumentException("invalid data key");
                }
                Object v = e.getValue();
                if (v == null || v instanceof Boolean || v instanceof Number) {
                    clean.put(e.getKey(), v);
                } else if (v instanceof CharSequence || v instanceof UUID) {
                    String s = v.toString();
                    if (s.length() > MAX_VALUE) {
                        throw new IllegalArgumentException("data value '" + e.getKey() + "' is longer than " + MAX_VALUE + " characters");
                    }
                    clean.put(e.getKey(), s);
                } else {
                    throw new IllegalArgumentException("data value '" + e.getKey() + "' must be a scalar");
                }
            }
        }
        try {
            return mapper.writeValueAsString(clean);
        } catch (JsonProcessingException e) {
            throw new IllegalArgumentException("data cannot be serialised");
        }
    }
}
