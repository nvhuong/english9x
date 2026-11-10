package com.english9x.billing.license;

import com.english9x.audit.api.AuditLog;
import com.english9x.billing.api.events.SeatAssigned;
import com.english9x.billing.api.events.SeatRevoked;
import com.english9x.billing.internal.BillingProblems;
import com.english9x.billing.license.LicenseDtos.SeatHolder;
import com.english9x.billing.license.LicenseRepository.License;
import com.english9x.billing.license.LicenseRepository.Seat;
import com.english9x.domain.billing.SeatPool;
import com.english9x.identity.api.UserDirectory;
import com.english9x.identity.api.UserView;
import com.english9x.org.api.CurrentContextHolder;
import java.time.Clock;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Assigning and taking back seats (spec section 11, F43), by the ORG_ADMIN of the organisation. Each action runs in one transaction with its audit
 * record and its event. The service answers the friendly errors with {@link SeatPool} (the domain rule) and the licence's facts; the database is the
 * arbiter for what it cannot know under concurrency: {@code seat_assignments_guard} locks the licence row and counts the live seats, so two
 * requests for the last seat serialise there and exactly one succeeds (the other gets 409 no-seat-available). The same triggers refuse a course
 * outside the licence, a user outside the organisation and an entitlement that outlives the licence, whatever this code does.
 */
@Service
class SeatService {

    private final LicenseRepository repo;
    private final LicenseService licenses;
    private final AuditLog audit;
    private final UserDirectory users;
    private final Clock clock;
    private final ApplicationEventPublisher events;

    SeatService(LicenseRepository repo, LicenseService licenses, AuditLog audit, UserDirectory users, Clock clock, ApplicationEventPublisher events) {
        this.repo = repo;
        this.licenses = licenses;
        this.audit = audit;
        this.users = users;
        this.clock = clock;
        this.events = events;
    }

    private static UUID actingUser() {
        return CurrentContextHolder.current().orElseThrow(() -> new AccessDeniedException("no user")).userId();
    }

    @Transactional
    SeatHolder assign(UUID orgId, UUID licenseId, UUID userId, List<UUID> courseIdList) {
        licenses.requireOrgAdmin(orgId);
        License lic = repo.find(licenseId).filter(l -> l.orgId().equals(orgId)).orElseThrow(BillingProblems::notFound);
        Instant now = clock.instant();
        if (!"ACTIVE".equals(lic.status()) || lic.validFrom().isAfter(now) || !now.isBefore(lic.validUntil())) {
            throw BillingProblems.conflict("license-not-active", "Licence not active", "This licence is not active: it is waiting for payment, cancelled or over.");
        }
        Set<UUID> courseIds = new LinkedHashSet<>(courseIdList);
        Set<UUID> licensed = new LinkedHashSet<>();
        repo.coursesOf(List.of(licenseId)).getOrDefault(licenseId, List.of()).forEach(c -> licensed.add(c.id()));
        if (!licensed.containsAll(courseIds)) {
            throw BillingProblems.unprocessable("course-not-in-license", "Course not in the licence", "courseIds", "CourseNotInLicense", "every course must be one of the licence's courses");
        }
        if (!repo.isMember(orgId, userId)) {
            throw BillingProblems.unprocessable("not-an-org-member", "Not a member of the organisation", "userId", "NotOrgMember", "only a member of the organisation can hold a seat");
        }
        SeatPool pool = new SeatPool(lic.seats());
        for (Seat s : repo.liveSeats(licenseId)) {
            pool.assign(s.userId().toString());
        }
        try {
            pool.assign(userId.toString());
        } catch (IllegalStateException e) {
            throw pool.isAssigned(userId.toString()) ? alreadyAssigned() : noSeat();
        }
        UUID seatId = UUID.randomUUID();
        try {
            repo.insertSeat(seatId, licenseId, orgId, userId, now, actingUser());
        } catch (DataIntegrityViolationException e) {
            throw refused(e);
        }
        for (UUID courseId : courseIds) {
            repo.insertSeatEntitlement(userId, courseId, now, lic.validUntil(), seatId, orgId);
        }
        Map<String, Object> detail = new LinkedHashMap<>();
        detail.put("licenseId", licenseId.toString());
        detail.put("userId", userId.toString());
        detail.put("courseIds", courseIds.stream().map(UUID::toString).toList());
        audit.record("seat.assign", "seat", seatId, detail);
        events.publishEvent(new SeatAssigned(licenseId, userId, List.copyOf(courseIds)));
        Optional<UserView> who = users.findById(userId);
        return new SeatHolder(userId, who.map(UserView::displayName).orElse(null), who.map(UserView::email).orElse(null), List.copyOf(courseIds), now);
    }

    /** Ends the person's seat now: the database ends its entitlements in the same statement, and the seat is free again. */
    @Transactional
    void revoke(UUID orgId, UUID licenseId, UUID userId) {
        licenses.requireOrgAdmin(orgId);
        License lic = repo.find(licenseId).filter(l -> l.orgId().equals(orgId)).orElseThrow(BillingProblems::notFound);
        Seat seat = repo.liveSeat(licenseId, userId).orElseThrow(BillingProblems::notFound);
        Map<UUID, List<UUID>> held = repo.seatCourses(licenseId);
        SeatPool pool = new SeatPool(lic.seats());
        for (Seat s : repo.liveSeats(licenseId)) {
            pool.assign(s.userId().toString());
        }
        pool.revoke(userId.toString());
        if (!repo.revokeSeat(seat.id(), clock.instant())) {
            throw BillingProblems.notFound();
        }
        Map<String, Object> detail = new LinkedHashMap<>();
        detail.put("licenseId", licenseId.toString());
        detail.put("userId", userId.toString());
        detail.put("courseIds", new ArrayList<>(held.getOrDefault(seat.id(), List.of()).stream().map(UUID::toString).toList()));
        audit.record("seat.revoke", "seat", seat.id(), detail);
        events.publishEvent(new SeatRevoked(licenseId, userId));
    }

    private static RuntimeException refused(DataIntegrityViolationException e) {
        String m = String.valueOf(e.getMostSpecificCause().getMessage());
        if (m.contains("seat capacity exceeded")) {
            return noSeat();
        }
        if (m.contains("seat_assignments_live_uq")) {
            return alreadyAssigned();
        }
        if (m.contains("not a member")) {
            return BillingProblems.unprocessable("not-an-org-member", "Not a member of the organisation", "userId", "NotOrgMember", "only a member of the organisation can hold a seat");
        }
        if (m.contains("licence is not active")) {
            return BillingProblems.conflict("license-not-active", "Licence not active", "This licence is not active: it is waiting for payment, cancelled or over.");
        }
        return e;
    }

    private static RuntimeException noSeat() {
        return BillingProblems.conflict("no-seat-available", "No seat available", "Every seat of this licence is taken. Take one back first.");
    }

    private static RuntimeException alreadyAssigned() {
        return BillingProblems.conflict("seat-already-assigned", "Seat already assigned", "This person already holds a seat of this licence.");
    }
}
