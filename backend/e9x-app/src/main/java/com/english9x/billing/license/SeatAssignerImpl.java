package com.english9x.billing.license;

import com.english9x.billing.api.SeatAssigner;
import com.english9x.billing.internal.BillingProblems;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

/** The public door to {@link SeatService}: one seat per person, skipped when they already hold one. */
@Component
class SeatAssignerImpl implements SeatAssigner {

    private final SeatService seats;
    private final LicenseRepository repo;
    private final LicenseService licenses;

    SeatAssignerImpl(SeatService seats, LicenseRepository repo, LicenseService licenses) {
        this.licenses = licenses;
        this.seats = seats;
        this.repo = repo;
    }

    @Override
    @Transactional
    public boolean assignUnlessHolding(UUID orgId, UUID licenseId, UUID userId, List<UUID> courseIds) {
        licenses.requireOrgAdmin(orgId);
        if (repo.liveSeat(licenseId, userId).isPresent()) {
            // nothing changes for them, but a request that names a course outside the licence is wrong whoever it concerns
            repo.find(licenseId).filter(l -> l.orgId().equals(orgId)).orElseThrow(BillingProblems::notFound);
            Set<UUID> licensed = new HashSet<>();
            repo.coursesOf(List.of(licenseId)).getOrDefault(licenseId, List.of()).forEach(c -> licensed.add(c.id()));
            if (!licensed.containsAll(courseIds)) {
                throw BillingProblems.unprocessable("course-not-in-license", "Course not in the licence", "courseIds", "CourseNotInLicense", "every course must be one of the licence's courses");
            }
            return false;
        }
        seats.assign(orgId, licenseId, userId, courseIds);
        return true;
    }
}
