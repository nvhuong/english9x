package com.english9x.billing.internal;

import com.english9x.billing.api.Entitlements;
import com.english9x.org.api.CurrentContextHolder;
import java.time.Duration;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;
import org.springframework.context.annotation.Profile;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;

/**
 * E2E only (profile {@code e2e}; the seed secret is checked by platform.e2e for every route under /api/test/seed): asks the REAL learning-access gate
 * as a person, because the lesson page and the exam room that call it arrive with P3.1 and P3.3.
 */
@RestController
@Profile("e2e")
class BillingGateProbe {

    record GateRequest(UUID userId, UUID courseId, Integer attemptStartedDaysAgo) {}

    private final Entitlements gate;

    BillingGateProbe(Entitlements gate) {
        this.gate = gate;
    }

    /** What the gate answers for that person right now: new learning, and (when asked) continuing an attempt that began that many days ago. */
    @PostMapping("/api/test/seed/billing/gate")
    Map<String, Object> gate(@RequestBody GateRequest r) {
        Map<String, Object> out = new LinkedHashMap<>();
        try (CurrentContextHolder.Scope ignored = CurrentContextHolder.bind(r.userId(), null)) {
            Instant now = Instant.now();
            out.put("activeFor", gate.activeFor(r.userId(), r.courseId(), now));
            if (r.attemptStartedDaysAgo() != null) {
                out.put("canContinueAttempt", gate.canContinueAttempt(r.userId(), r.courseId(), now.minus(Duration.ofDays(r.attemptStartedDaysAgo()))));
            }
        }
        return out;
    }
}
