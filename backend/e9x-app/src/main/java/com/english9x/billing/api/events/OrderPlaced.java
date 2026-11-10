package com.english9x.billing.api.events;

import java.util.UUID;

/** A learner placed an order that now waits for payment (spec section 13: every SYS_ADMIN is told). Published in the transaction that creates the order. */
public record OrderPlaced(UUID orderId, String orderCode, UUID userId, long amountVnd) {}
