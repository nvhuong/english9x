package com.english9x.billing.api.events;

import java.util.UUID;

/** A licence seat was taken back; the seat returns to the pool. */
public record SeatRevoked(UUID licenseId, UUID userId) {}
