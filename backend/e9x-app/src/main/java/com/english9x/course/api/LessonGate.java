package com.english9x.course.api;

import java.time.Instant;
import java.util.UUID;

/**
 * Decides whether a person may open a lesson (spec 8): first the entitlement ({@code billing.api.Entitlements#activeFor}, the single access rule), then the
 * unlock rule of the course ({@code UnlockPolicy} of the domain, over the person's progress). The person must be the one bound to the thread
 * ({@code CurrentContextHolder}): the entitlement is read under their row-level security.
 */
public interface LessonGate {

    /** @throws LessonNotFoundException when there is no such lesson or the caller may not read it */
    LessonAccess check(UUID userId, UUID lessonId, Instant now);
}
