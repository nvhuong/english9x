package com.english9x.notification.internal;

/** The channel refused this delivery for good (for example a Telegram chat that blocked the bot): retrying cannot help. */
final class PermanentDeliveryException extends Exception {

    private static final long serialVersionUID = 1L;
    private final String reason;

    PermanentDeliveryException(String reason) {
        super(reason, null, false, false);
        this.reason = reason;
    }

    String reason() {
        return reason;
    }
}
