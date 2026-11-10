package com.english9x.billing.license;

import com.english9x.audit.api.AuditLog;
import com.english9x.billing.internal.BillingProblems;
import com.english9x.billing.license.LicenseDtos.Activation;
import com.english9x.billing.license.LicenseDtos.CreateLicenseRequest;
import com.english9x.billing.license.LicenseDtos.LicenseCourse;
import com.english9x.billing.license.LicenseDtos.LicenseCreated;
import com.english9x.billing.license.LicenseDtos.LicenseView;
import com.english9x.billing.license.LicenseDtos.SeatHolder;
import com.english9x.billing.license.LicenseRepository.Course;
import com.english9x.billing.license.LicenseRepository.License;
import com.english9x.billing.license.LicenseRepository.Seat;
import com.english9x.billing.order.LicenseOrders;
import com.english9x.billing.order.LicenseOrders.Placed;
import com.english9x.identity.api.UserDirectory;
import com.english9x.identity.api.UserView;
import com.english9x.org.api.Authz;
import com.english9x.org.api.CurrentContextHolder;
import com.english9x.org.api.Role;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Organisation licences (spec section 11). A real SYS_ADMIN creates them: a CONTRACT licence is active at once, a QR licence waits for the payment
 * of its order (P5.2's confirmation activates it). The price and the length are written once and never recomputed. The organisation's ORG_ADMIN reads
 * its own licences. Seats are in {@link SeatService}.
 */
@Service
class LicenseService implements LicenseActivation {

    /** Months are counted on the Vietnamese calendar, as for plans. */
    private static final ZoneId ZONE = ZoneId.of("Asia/Ho_Chi_Minh");
    private static final int ADMIN_LIST_LIMIT = 200;

    private final LicenseRepository repo;
    private final Authz authz;
    private final AuditLog audit;
    private final LicenseOrders orders;
    private final UserDirectory users;
    private final Clock clock;

    LicenseService(LicenseRepository repo, Authz authz, AuditLog audit, LicenseOrders orders, UserDirectory users, Clock clock) {
        this.repo = repo;
        this.authz = authz;
        this.audit = audit;
        this.orders = orders;
        this.users = users;
        this.clock = clock;
    }

    static Instant endOf(Instant from, int months) {
        return from.atZone(ZONE).plusMonths(months).toInstant();
    }

    @Transactional
    LicenseCreated create(CreateLicenseRequest req) {
        authz.require(Role.SYS_ADMIN, Optional.empty());
        UUID admin = CurrentContextHolder.current().orElseThrow(() -> new AccessDeniedException("no user")).userId();
        boolean contract = req.activation() == Activation.CONTRACT;
        String contractNo = req.contractNo() == null || req.contractNo().isBlank() ? null : req.contractNo().strip();
        if (contract && contractNo == null) {
            throw BillingProblems.validation("contractNo", "Required", "a CONTRACT licence needs its contract number");
        }
        if (!contract && contractNo != null) {
            throw BillingProblems.validation("contractNo", "NotAllowed", "a QR licence has no contract number");
        }
        List<UUID> ids = new ArrayList<>(new LinkedHashSet<>(req.courseIds()));
        if (!repo.orgExists(req.orgId())) {
            throw BillingProblems.notFound();
        }
        List<Course> courses = repo.licensableCourses(ids);
        if (courses.size() != ids.size()) {
            throw BillingProblems.validation("courseIds", "CourseNotAvailable", "every course must be a published English9x course");
        }
        UUID payer = null;
        if (!contract) {
            payer = repo.defaultPayer(req.orgId()).orElseThrow(() -> BillingProblems.unprocessable("org-has-no-admin", "Organisation has no administrator", "orgId", "NoOrgAdmin",
                    "a QR licence is paid by an ORG_ADMIN of the organisation, and it has none"));
        }
        Instant now = clock.instant();
        UUID id = UUID.randomUUID();
        repo.insertLicense(id, req.orgId(), req.seats(), req.months(), req.priceVnd(), req.activation().name(), contractNo, contract ? "ACTIVE" : "PENDING_PAYMENT",
                contract ? now : null, contract ? endOf(now, req.months()) : null, now, admin);
        for (Course c : courses) {
            repo.insertLicenseCourse(id, req.orgId(), c.id());
        }
        Placed placed = null;
        if (!contract) {
            placed = orders.place(id, payer, req.priceVnd(), req.months(), courses.stream().map(c -> new LicenseOrders.Course(c.id(), c.code())).toList());
        }
        Map<String, Object> detail = new LinkedHashMap<>();
        detail.put("orgId", req.orgId().toString());
        detail.put("seats", req.seats());
        detail.put("months", req.months());
        detail.put("priceVnd", req.priceVnd());
        detail.put("activation", req.activation().name());
        detail.put("courses", courses.size());
        if (contractNo != null) {
            detail.put("contractNo", contractNo);
        }
        if (placed != null) {
            detail.put("orderCode", placed.orderCode());
        }
        audit.record("license.create", "license", id, detail);
        LicenseView view = view(repo.find(id).orElseThrow(), false);
        return new LicenseCreated(view, placed == null ? null : placed.transferNote(), placed == null ? null : placed.qrPayload(), placed == null ? null : placed.payTo());
    }

    /** The courses a licence may cover (published English9x courses): what the administrator's form offers. */
    @Transactional(readOnly = true)
    List<LicenseCourse> licensableCourses() {
        authz.require(Role.SYS_ADMIN, Optional.empty());
        return repo.allLicensableCourses().stream().map(c -> new LicenseCourse(c.id(), c.code(), c.title())).toList();
    }

    /** Every licence, newest first (at most 200). */
    @Transactional(readOnly = true)
    List<LicenseView> listAll() {
        authz.require(Role.SYS_ADMIN, Optional.empty());
        return views(repo.all(ADMIN_LIST_LIMIT), false);
    }

    /** The organisation's own licences with who holds each seat. The organisation of the context must be the one asked for. */
    @Transactional(readOnly = true)
    List<LicenseView> ofOrg(UUID orgId) {
        requireOrgAdmin(orgId);
        return views(repo.ofOrg(orgId), true);
    }

    @Transactional
    @Override
    public void activate(UUID licenseId, Instant at) {
        License l = repo.find(licenseId).orElseThrow(() -> new IllegalStateException("licence not visible"));
        Instant until = endOf(at, l.months());
        if (!repo.activate(licenseId, at, until)) {
            throw new IllegalStateException("licence is not waiting for payment");
        }
        Map<String, Object> detail = new LinkedHashMap<>();
        detail.put("change", "ACTIVATED");
        detail.put("orgId", l.orgId().toString());
        detail.put("validUntil", until.toString());
        audit.record("license.change", "license", licenseId, detail);
    }

    @Transactional
    @Override
    public void cancel(UUID licenseId) {
        License l = repo.find(licenseId).orElseThrow(() -> new IllegalStateException("licence not visible"));
        if (!repo.cancel(licenseId)) {
            throw new IllegalStateException("licence is not waiting for payment");
        }
        audit.record("license.change", "license", licenseId, Map.of("change", "CANCELLED", "orgId", l.orgId().toString()));
    }

    /** The path organisation must be the one of the context (404 otherwise, whether or not the other exists), and the caller its ORG_ADMIN (403). */
    void requireOrgAdmin(UUID orgId) {
        Optional<UUID> ctx = CurrentContextHolder.current().flatMap(CurrentContextHolder.Binding::orgId);
        if (ctx.isEmpty()) {
            authz.require(Role.ORG_ADMIN, Optional.empty());   // fails: ORG_ADMIN is a role of an organisation context
            throw new AccessDeniedException("an organisation context is required");
        }
        if (!ctx.get().equals(orgId)) {
            throw BillingProblems.notFound();
        }
        authz.require(Role.ORG_ADMIN, Optional.empty());
    }

    LicenseView viewOf(License l) {
        return view(l, true);
    }

    private List<LicenseView> views(List<License> licenses, boolean withHolders) {
        List<UUID> ids = licenses.stream().map(License::id).toList();
        Map<UUID, List<Course>> courses = repo.coursesOf(ids);
        Map<UUID, String> codes = repo.orderCodes(ids);
        List<LicenseView> out = new ArrayList<>();
        for (License l : licenses) {
            out.add(build(l, courses.getOrDefault(l.id(), List.of()), codes.get(l.id()), withHolders));
        }
        return out;
    }

    private LicenseView view(License l, boolean withHolders) {
        return views(List.of(l), withHolders).get(0);
    }

    private LicenseView build(License l, List<Course> courses, String orderCode, boolean withHolders) {
        List<SeatHolder> holders = new ArrayList<>();
        int used;
        if (withHolders) {
            List<Seat> seats = repo.liveSeats(l.id());
            Map<UUID, List<UUID>> held = repo.seatCourses(l.id());
            for (Seat s : seats) {
                Optional<UserView> who = users.findById(s.userId());
                holders.add(new SeatHolder(s.userId(), who.map(UserView::displayName).orElse(null), who.map(UserView::email).orElse(null), held.getOrDefault(s.id(), List.of()), s.assignedAt()));
            }
            used = seats.size();
        } else {
            used = repo.liveSeats(l.id()).size();
        }
        return new LicenseView(l.id(), l.orgId(), l.seats(), used, l.months(), l.priceVnd(), Activation.valueOf(l.activation()), l.contractNo(), l.status(), l.validFrom(), l.validUntil(),
                l.createdAt(), courses.stream().map(c -> new LicenseCourse(c.id(), c.code(), c.title())).toList(), orderCode, holders);
    }
}
