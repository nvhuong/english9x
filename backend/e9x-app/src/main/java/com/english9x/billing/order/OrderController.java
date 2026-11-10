package com.english9x.billing.order;

import com.english9x.billing.order.OrderDtos.CreateOrderRequest;
import com.english9x.billing.order.OrderDtos.OrderCreated;
import com.english9x.billing.order.OrderDtos.OrderView;
import jakarta.validation.Valid;
import java.util.List;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

/** A learner's own orders. Nobody reads another person's order here (the administrators' queue is P5.2). */
@RestController
class OrderController {

    private final OrderService orders;

    OrderController(OrderService orders) {
        this.orders = orders;
    }

    @PostMapping("/api/orders")
    @ResponseStatus(HttpStatus.CREATED)
    OrderCreated create(@Valid @RequestBody CreateOrderRequest body) {
        return orders.create(body);
    }

    /** The caller's orders, newest first. */
    @GetMapping("/api/orders")
    List<OrderView> mine() {
        return orders.mine();
    }

    /** One of the caller's orders by its code; any other code is a 404. */
    @GetMapping("/api/orders/{code}")
    OrderView one(@PathVariable String code) {
        return orders.mine(code);
    }
}
