package com.english9x.billing.payment;

import com.english9x.billing.payment.OrderAdminDtos.AdminOrderView;
import com.english9x.billing.payment.OrderAdminDtos.ConfirmOrderRequest;
import com.english9x.billing.payment.OrderAdminDtos.RejectOrderRequest;
import jakarta.validation.Valid;
import java.util.List;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/** The administrators' queue of orders and their decision. SYS_ADMIN only. */
@RestController
class OrderAdminController {

    private final OrderAdminService orders;

    OrderAdminController(OrderAdminService orders) {
        this.orders = orders;
    }

    @GetMapping("/api/admin/orders")
    List<AdminOrderView> list(@RequestParam(required = false) String status, @RequestParam(required = false) String q) {
        return orders.list(status, q);
    }

    /** Payment received: creates the entitlements from now. 409 when the order is no longer waiting for payment. */
    @PostMapping("/api/admin/orders/{code}/confirm")
    AdminOrderView confirm(@PathVariable String code, @Valid @RequestBody(required = false) ConfirmOrderRequest body) {
        return orders.confirm(code, body == null ? null : body.bankRef());
    }

    /** Payment not accepted: 422 without a reason, 409 when the order is no longer waiting for payment. */
    @PostMapping("/api/admin/orders/{code}/reject")
    AdminOrderView reject(@PathVariable String code, @RequestBody(required = false) RejectOrderRequest body) {
        return orders.reject(code, body == null ? null : body.reason());
    }
}
