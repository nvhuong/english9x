package com.english9x.domain.billing;

import java.time.Instant;
import java.util.List;

public final class AccessPolicy {
    private AccessPolicy() {}

    public static boolean canStartLearning(List<Entitlement> ents, String userId, String courseId, Instant now) {
        return anyActive(ents, userId, courseId, now);
    }

    /** An attempt may continue if the learner was entitled when it started, even if it expired since. */
    public static boolean canContinueAttempt(List<Entitlement> ents, String userId, String courseId,
                                             Instant attemptStartedAt) {
        return anyActive(ents, userId, courseId, attemptStartedAt);
    }

    private static boolean anyActive(List<Entitlement> ents, String userId, String courseId, Instant t) {
        for (Entitlement e : ents) {
            if (e.userId().equals(userId) && e.courseId().equals(courseId) && e.isActiveAt(t)) {
                return true;
            }
        }
        return false;
    }
}
