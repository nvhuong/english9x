package com.english9x.billing.api.events;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

/** An administrator confirmed payment of an order; entitlements for the courses were created. */
public record OrderConfirmed(UUID orderId, UUID userId, List<UUID> courseIds, Instant confirmedAt) {}
