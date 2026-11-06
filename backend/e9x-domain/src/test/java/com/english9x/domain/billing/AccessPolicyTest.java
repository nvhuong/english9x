package com.english9x.domain.billing;

import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

import java.time.Instant;
import java.util.List;

public class AccessPolicyTest {
    private static final Instant FROM = Instant.parse("2026-11-01T00:00:00Z");
    private static final Instant UNTIL = Instant.parse("2027-05-01T00:00:00Z");
    private static final List<Entitlement> ENTS =
            List.of(new Entitlement("u1", "c1", FROM, UNTIL, EntitlementSource.ORDER, "D2611A"));

    @Test
    public void activeEntitlementAllows() {
        assertTrue(AccessPolicy.canStartLearning(ENTS, "u1", "c1", FROM));
        assertTrue(AccessPolicy.canStartLearning(ENTS, "u1", "c1", UNTIL.minusSeconds(1)));
    }

    @Test
    public void expiredEntitlementDenies() {
        assertFalse(AccessPolicy.canStartLearning(ENTS, "u1", "c1", UNTIL));
        assertFalse(AccessPolicy.canStartLearning(ENTS, "u1", "c1", FROM.minusSeconds(1)));
    }

    @Test
    public void otherCourseDenies() {
        assertFalse(AccessPolicy.canStartLearning(ENTS, "u1", "c2", FROM.plusSeconds(5)));
        assertFalse(AccessPolicy.canStartLearning(ENTS, "u2", "c1", FROM.plusSeconds(5)));
    }

    @Test
    public void attemptStartedWhileEntitledSurvivesExpiry() {
        assertTrue(AccessPolicy.canContinueAttempt(ENTS, "u1", "c1", UNTIL.minusSeconds(1)));
    }

    @Test
    public void attemptStartedAfterExpiryDenied() {
        assertFalse(AccessPolicy.canContinueAttempt(ENTS, "u1", "c1", UNTIL.plusSeconds(1)));
    }
}
