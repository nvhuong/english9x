package com.english9x.notification.internal;

/** The text kept for a failed send: an exception's own message is never used (it quotes addresses and URLs, the latter holding the bot token). */
final class DeliveryFailures {

    private DeliveryFailures() {}

    /** {@code http-500}-style for {@link HttpDeliveryException}, otherwise the simple class name of the exception. */
    static String reason(Throwable e) {
        return e instanceof HttpDeliveryException h ? "http-" + h.status() : e.getClass().getSimpleName();
    }
}
