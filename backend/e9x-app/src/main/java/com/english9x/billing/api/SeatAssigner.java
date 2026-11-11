package com.english9x.billing.api;

import java.util.List;
import java.util.UUID;

/**
 * Gives a person a seat of an organisation's licence (P5.3), for the classroom module's "give this course to the whole class". It is the same seat path as
 * the licence screen ({@code POST /api/orgs/{id}/licenses/{id}/seats}): same checks (the ORG_ADMIN of the organisation, an active licence, courses of the
 * licence, a member of the organisation, a free seat), same audit record, same {@code SeatAssigned} event. Nothing here counts seats itself.
 */
public interface SeatAssigner {

    /**
     * Joins the caller's transaction (or opens one).
     *
     * @return {@code true} when a seat was given now; {@code false} when the person already holds a live seat of this licence (nothing changes)
     * @throws org.springframework.web.ErrorResponseException 409 {@code no-seat-available} / {@code license-not-active}, 422 {@code course-not-in-license} /
     *         {@code not-an-org-member}, 404 for an unknown licence; {@code AccessDeniedException} when the caller is not the organisation's ORG_ADMIN
     */
    boolean assignUnlessHolding(UUID orgId, UUID licenseId, UUID userId, List<UUID> courseIds);
}
