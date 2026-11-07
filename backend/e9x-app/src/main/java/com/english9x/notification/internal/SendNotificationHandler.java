package com.english9x.notification.internal;

import com.english9x.notification.api.Channel;
import com.english9x.notification.api.EventType;
import com.english9x.notification.api.Message;
import com.english9x.notification.api.NotificationChannel;
import com.english9x.notification.api.Recipient;
import com.english9x.platform.jobs.api.Job;
import com.english9x.platform.jobs.api.JobExecutor;
import com.english9x.platform.jobs.api.JobHandler;
import com.english9x.platform.jobs.api.JobResult;
import com.english9x.platform.jobs.api.WorkerJdbc;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Sends one delivery (one channel of one notification). Runs as the worker login, which can reach no notification table: what it
 * needs comes from {@code notification_delivery_load} and the outcome goes to {@code notification_delivery_finish}. Idempotent: a
 * delivery that is no longer PENDING loads nothing. A failure is a job failure (retried through the queue with
 * {@code e9x.jobs.send_notification.retry-delays}); past the last retry {@link #onExhausted} records FAILED. The in-app notification is
 * never touched here. Neither the address, the chat id, the text nor an exception message is logged or stored.
 */
@JobHandler("send_notification")
class SendNotificationHandler implements JobExecutor {

    private static final Logger LOG = LoggerFactory.getLogger(SendNotificationHandler.class);

    private final WorkerJdbc worker;
    private final Map<Channel, NotificationChannel> channels = new EnumMap<>(Channel.class);
    private final Templates templates;
    private final ObjectMapper mapper;

    SendNotificationHandler(WorkerJdbc worker, List<NotificationChannel> all, Templates templates, ObjectMapper mapper) {
        this.worker = worker;
        all.forEach(c -> channels.put(c.id(), c));
        this.templates = templates;
        this.mapper = mapper;
    }

    private record Loaded(String eventType, String data, String locale, String displayName, String email, Long chatId) {}

    @Override
    public JobResult handle(Job job) throws Exception {
        UUID id = UUID.fromString(job.payload().path("notificationId").asText());
        Channel channel = Channel.valueOf(job.payload().path("channel").asText());
        NotificationChannel sender = channels.get(channel);
        if (sender == null || !channel.optional()) {
            return JobResult.failure("no-channel");
        }
        List<Loaded> rows = worker.jdbc().query("select r_event_type, r_data::text, r_locale, r_display_name, r_email, r_chat_id "
                + "from notification_delivery_load(?, ?)", (rs, i) -> new Loaded(rs.getString(1), rs.getString(2), rs.getString(3),
                rs.getString(4), rs.getString(5), (Long) rs.getObject(6)), id, channel.name());
        if (rows.isEmpty()) {
            return JobResult.success(); // already sent, skipped (switched off / unlinked) or finished by an earlier run
        }
        Loaded l = rows.get(0);
        EventType type;
        try {
            type = EventType.valueOf(l.eventType());
        } catch (IllegalArgumentException e) {
            finish(id, channel, "FAILED", "unknown-event-type");
            return JobResult.success();
        }
        JsonNode data = mapper.readTree(l.data());
        Map<String, Object> values = mapper.convertValue(data, new com.fasterxml.jackson.core.type.TypeReference<Map<String, Object>>() {});
        Message message = templates.render(type, values, l.locale());
        Recipient recipient = new Recipient(l.displayName(), l.locale(), l.email(), l.chatId());
        try {
            sender.send(recipient, message);
        } catch (PermanentDeliveryException e) {
            finish(id, channel, "FAILED", e.reason());
            LOG.warn("notification {} {} refused for good: {}", id, channel, e.reason());
            return JobResult.success();
        } catch (Exception e) {
            // class (and the HTTP status the channel put into its own exception) only: other messages quote addresses and URLs
            return JobResult.failure(DeliveryFailures.reason(e));
        }
        finish(id, channel, "SENT", null);
        return JobResult.success();
    }

    @Override
    public void onExhausted(Job job, String reason) {
        finish(UUID.fromString(job.payload().path("notificationId").asText()), Channel.valueOf(job.payload().path("channel").asText()),
                "FAILED", reason == null ? "failed" : reason);
    }

    private void finish(UUID id, Channel channel, String status, String error) {
        worker.jdbc().queryForObject("select notification_delivery_finish(?, ?, ?, ?)", Boolean.class, id, channel.name(), status, error);
    }
}
