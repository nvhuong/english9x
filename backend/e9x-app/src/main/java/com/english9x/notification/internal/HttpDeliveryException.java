package com.english9x.notification.internal;

/** A non-success answer of an HTTP API. The message deliberately has no URL: Telegram's contains the bot token. */
final class HttpDeliveryException extends Exception {

    private static final long serialVersionUID = 1L;
    private final int status;

    HttpDeliveryException(int status) {
        super("HTTP " + status, null, false, false);
        this.status = status;
    }

    int status() {
        return status;
    }
}
