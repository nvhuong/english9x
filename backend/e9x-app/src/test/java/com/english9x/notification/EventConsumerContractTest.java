package com.english9x.notification;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.english9x.billing.api.events.OrderConfirmed;
import com.english9x.billing.api.events.OrderRejected;
import com.english9x.billing.api.events.SeatAssigned;
import com.english9x.billing.api.events.SeatRevoked;
import com.english9x.exam.api.events.AttemptSubmitted;
import com.english9x.grading.api.events.AttemptScored;
import com.english9x.workflow.api.events.ContentPublished;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.Test;

/**
 * Stands in for a later consumer (notification, analytics, ...): it lives in a module of its own and references
 * every event only through the publishers' {@code api.events} packages. ModularityTest fails if Modulith would
 * refuse that, and the shapes below fail to compile if a contract changes.
 */
class EventConsumerContractTest {

    @Test
    void consumerBuildsEveryEventWithTheContractedShape() {
        UUID id = UUID.randomUUID();
        Instant now = Instant.now();
        AttemptSubmitted submitted = new AttemptSubmitted(id, id, id, Optional.of(id), now, "USER", false);
        AttemptScored scored = new AttemptScored(id, id, Map.of("L", 7.5), true);
        ContentPublished published = new ContentPublished("TEST", id, Optional.empty(), "IELTS_AC");
        OrderConfirmed confirmed = new OrderConfirmed(id, id, List.of(id), now);
        OrderRejected rejected = new OrderRejected(id, "no payment");
        SeatAssigned assigned = new SeatAssigned(id, id, List.of(id));
        SeatRevoked revoked = new SeatRevoked(id, id);

        assertEquals("USER", submitted.reason());
        assertTrue(scored.official());
        assertTrue(published.ownerOrgId().isEmpty());
        assertEquals(List.of(id), confirmed.courseIds());
        assertEquals("no payment", rejected.reason());
        assertEquals(id, assigned.licenseId());
        assertEquals(id, revoked.userId());
    }
}
