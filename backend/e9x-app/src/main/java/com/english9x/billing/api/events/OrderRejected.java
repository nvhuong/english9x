package com.english9x.billing.api.events;

import java.util.UUID;

/** An administrator rejected an order. {@code userId} is the person who ordered; {@code reason} is free text the administrator wrote (at most 500 characters). */
public record OrderRejected(UUID orderId, UUID userId, String reason) {}
