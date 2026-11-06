package com.english9x.billing.api.events;

import java.util.List;
import java.util.UUID;

/** An organisation administrator assigned a licence seat to a user for some courses. */
public record SeatAssigned(UUID licenseId, UUID userId, List<UUID> courseIds) {}
