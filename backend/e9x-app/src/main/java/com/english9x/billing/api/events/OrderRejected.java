package com.english9x.billing.api.events;

import java.util.UUID;

/** An administrator rejected an order. */
public record OrderRejected(UUID orderId, String reason) {}
