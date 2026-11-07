package com.english9x.notification.api;

import java.util.Map;
import java.util.UUID;

/**
 * Tells a person about an event (spec §13). In one transaction it stores the in-app notification (always) and records, for each
 * optional channel the person has enabled for this event, a delivery plus a {@code send_notification} job; nothing is sent here, so the
 * call is a handful of database writes and never waits for a mail server or Telegram.
 *
 * <p>Call it from a module-event listener ({@code @ApplicationModuleListener}: after the business transaction committed, delivered at
 * least once) and pass the dedupe key described below, so a redelivered event notifies nobody twice. A failed send never reaches the
 * business transaction. Calling it inside a business transaction is possible (it joins it), but then a failure here rolls that
 * transaction back: prefer the listener.
 *
 * <p>Security mail (verify email, password reset, already registered) is NOT sent through here and is never switched off by
 * preferences; see identity.
 */
public interface NotificationService {

    /** Notifies without deduplication (every call creates a notification). For one-off producers that cannot be redelivered. */
    default void notify(EventType type, UUID userId, Map<String, Object> data) {
        notify(type, userId, data, "once:" + UUID.randomUUID());
    }

    /**
     * Notifies once per ({@code userId}, {@code dedupeKey}): calling again with the same pair does nothing. Derive the key from the
     * source event's natural id, e.g. {@code "review-requested:" + reviewId}.
     *
     * @param data scalars only (String, Number, Boolean, UUID, null), at most 20 keys, values up to 500 characters; never a content
     *             title, an answer or a secret. Shown to the person, so it must be something they may see.
     * @return the number of optional-channel jobs queued; {@code 0} also when this was a duplicate, the user does not exist or the
     *         person has switched every optional channel off (the in-app notification exists in the first two cases only when new)
     */
    int notify(EventType type, UUID userId, Map<String, Object> data, String dedupeKey);
}
