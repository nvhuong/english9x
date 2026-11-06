package com.english9x.domain.billing;

import java.time.Instant;

public record Entitlement(String userId, String courseId, Instant validFrom, Instant validUntil,
                          EntitlementSource source, String sourceId) {
    public Entitlement {
        if (!validUntil.isAfter(validFrom)) {
            throw new IllegalArgumentException("validUntil must be after validFrom");
        }
    }

    boolean isActiveAt(Instant t) {
        return !t.isBefore(validFrom) && t.isBefore(validUntil);
    }
}
