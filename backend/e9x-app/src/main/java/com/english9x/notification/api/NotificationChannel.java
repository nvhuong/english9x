package com.english9x.notification.api;

/**
 * A way to reach a person. The module has three: {@code IN_APP} (the stored notification itself), {@code EMAIL} (SMTP) and
 * {@code TELEGRAM} (Bot API). Optional channels are driven by the job {@code send_notification}: one job per (notification,
 * channel), retried through the job queue. {@link #send} throws when the delivery failed; the job then retries or, past the last
 * retry, records the failure. A channel must be idempotent enough to be called again after a crash, and must never put the
 * recipient's address, a token or the text in a log or an exception message.
 */
public interface NotificationChannel {

    Channel id();

    /** Delivers the message. Throws on failure (the exception class is recorded, never its message). */
    void send(Recipient recipient, Message message) throws Exception;
}
