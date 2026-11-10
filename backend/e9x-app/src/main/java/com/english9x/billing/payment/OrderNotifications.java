package com.english9x.billing.payment;

import com.english9x.billing.api.events.OrderConfirmed;
import com.english9x.billing.api.events.OrderPlaced;
import com.english9x.billing.api.events.OrderRejected;
import com.english9x.notification.api.EventType;
import com.english9x.notification.api.NotificationService;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.modulith.events.ApplicationModuleListener;
import org.springframework.stereotype.Component;

/**
 * Spec section 13, the three order notices, from the billing events. Lives in billing (not in notification) because billing already uses the
 * notification API for the plan notices, and the notification module may not depend on billing back. Runs after the business transaction
 * committed, on a pool thread with nobody bound, delivered at least once by the persistent event registry; every notification carries a dedupe key
 * built from the order id, so a redelivered event tells nobody twice.
 *
 * <p>Who hears: a new order goes to every SYS_ADMIN (ids from the narrow SECURITY DEFINER function {@code notification_sys_admins()}, which returns
 * nothing else); a confirmation or a rejection goes to the person who ordered. An order belongs to one person, and when that person is an
 * ORG_ADMIN who ordered, the notice reaches them as that person. The data is the order code (and the amount), never a course title or an account.
 * The rejection reason is text the administrator wrote: it is stored and escaped per channel by the templates, and cut to the 500 characters a
 * notification value may hold.
 */
@Component
class OrderNotifications {

    private static final int MAX_VALUE = 500;

    private final NotificationService notifications;
    private final JdbcTemplate jdbc;

    OrderNotifications(NotificationService notifications, JdbcTemplate jdbc) {
        this.notifications = notifications;
        this.jdbc = jdbc;
    }

    @ApplicationModuleListener
    void on(OrderPlaced e) {
        Map<String, Object> data = new LinkedHashMap<>();
        data.put("orderId", e.orderId().toString());
        data.put("orderCode", e.orderCode());
        data.put("amountVnd", e.amountVnd());
        for (UUID admin : jdbc.queryForList("select r_user_id from notification_sys_admins()", UUID.class)) {
            notifications.notify(EventType.ORDER_PENDING, admin, data, "order-pending:" + e.orderId());
        }
    }

    @ApplicationModuleListener
    void on(OrderConfirmed e) {
        notifications.notify(EventType.ORDER_CONFIRMED, e.userId(), Map.of("orderId", e.orderId().toString()), "order-confirmed:" + e.orderId());
    }

    @ApplicationModuleListener
    void on(OrderRejected e) {
        String reason = e.reason() == null ? "" : e.reason();
        if (reason.length() > MAX_VALUE) {
            reason = reason.substring(0, MAX_VALUE);
        }
        Map<String, Object> data = new LinkedHashMap<>();
        data.put("orderId", e.orderId().toString());
        data.put("reason", reason);
        notifications.notify(EventType.ORDER_REJECTED, e.userId(), data, "order-rejected:" + e.orderId());
    }
}
