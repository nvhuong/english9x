package com.english9x.billing.payment;

import com.english9x.audit.api.AuditLog;
import com.english9x.billing.api.events.OrderConfirmed;
import com.english9x.billing.api.events.OrderRejected;
import com.english9x.billing.internal.BillingProblems;
import com.english9x.billing.internal.BillingProperties;
import com.english9x.billing.payment.OrderAdminDtos.AdminOrderView;
import com.english9x.billing.payment.OrderAdminRepository.AdminOrder;
import com.english9x.domain.billing.Codes;
import com.english9x.domain.billing.Entitlement;
import com.english9x.domain.billing.Entitlements;
import com.english9x.domain.billing.Order;
import com.english9x.domain.billing.OrderStatus;
import com.english9x.domain.billing.Plan;
import com.english9x.domain.billing.TransferNote;
import com.english9x.identity.api.UserDirectory;
import com.english9x.identity.api.UserView;
import com.english9x.org.api.Authz;
import com.english9x.org.api.Role;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * The administrator decides a payment (spec section 11). Confirm and reject each run in ONE transaction: the order, the entitlements, the audit
 * record and the event stand or fall together. The price and the length come from the order (locked when it was placed), never from the plan.
 * Everything is checked against the database as the acting person: only a real SYS_ADMIN gets past {@link Authz} and the UPDATE policy of orders.
 */
@Service
class OrderAdminService {

    /** Months are counted on the Vietnamese calendar (no daylight saving, so a plan bought on the 31st ends on the last day of a shorter month). */
    private static final ZoneId PLAN_ZONE = ZoneId.of("Asia/Ho_Chi_Minh");
    static final int MAX_REASON = 500;
    private static final int LIST_LIMIT = 500;
    private static final int PAGE = 100;
    private static final Set<String> STATUSES = Set.of("PENDING_PAYMENT", "CONFIRMED", "REJECTED", "EXPIRED");

    private final OrderAdminRepository repo;
    private final Authz authz;
    private final AuditLog audit;
    private final UserDirectory users;
    private final BillingProperties props;
    private final Clock clock;
    private final ApplicationEventPublisher events;

    OrderAdminService(OrderAdminRepository repo, Authz authz, AuditLog audit, UserDirectory users, BillingProperties props, Clock clock, ApplicationEventPublisher events) {
        this.repo = repo;
        this.authz = authz;
        this.audit = audit;
        this.users = users;
        this.props = props;
        this.clock = clock;
        this.events = events;
    }

    /** Orders newest first, optionally of one status; {@code q} matches (case-insensitively) the code, the payer's email or name. At most 100. */
    @Transactional(readOnly = true)
    List<AdminOrderView> list(String status, String q) {
        authz.require(Role.SYS_ADMIN, Optional.empty());
        if (status != null && !STATUSES.contains(status)) {
            throw BillingProblems.validation("status", "InvalidStatus", "status must be one of " + STATUSES.stream().sorted().toList());
        }
        String needle = q == null || q.isBlank() ? null : q.strip().toLowerCase(Locale.ROOT);
        List<AdminOrder> orders = repo.list(status, LIST_LIMIT);
        Map<UUID, List<String>> codes = repo.courseCodes(orders.stream().map(AdminOrder::id).toList());
        Map<UUID, Optional<UserView>> people = new LinkedHashMap<>();
        List<AdminOrderView> out = new ArrayList<>();
        for (AdminOrder o : orders) {
            Optional<UserView> who = people.computeIfAbsent(o.userId(), users::findById);
            if (needle != null && !matches(needle, o, who)) {
                continue;
            }
            out.add(view(o, codes.getOrDefault(o.id(), List.of()), who));
            if (out.size() == PAGE) {
                break;
            }
        }
        return out;
    }

    private static boolean matches(String needle, AdminOrder o, Optional<UserView> who) {
        return o.code().toLowerCase(Locale.ROOT).contains(needle)
                || who.map(w -> w.email().toLowerCase(Locale.ROOT).contains(needle) || (w.displayName() != null && w.displayName().toLowerCase(Locale.ROOT).contains(needle))).orElse(false);
    }

    @Transactional
    AdminOrderView confirm(String code, String bankRef) {
        authz.require(Role.SYS_ADMIN, Optional.empty());
        AdminOrder o = load(code);
        String ref = bankRef == null || bankRef.isBlank() ? null : bankRef.strip();
        if (ref != null && ref.length() > 100) {
            throw BillingProblems.validation("bankRef", "Size", "bankRef must be at most 100 characters");
        }
        Instant now = clock.instant();
        Map<UUID, String> items = repo.items(o.id());
        // the domain object decides the dates; the amount, the length and the courses are the ones locked on the order
        Order domain = new Order(o.code(), o.userId().toString(), o.amountVnd(), items.keySet().stream().map(UUID::toString).toList(), o.createdAt());
        domain.confirm(now, ref);
        List<Entitlement> granted = Entitlements.forConfirmedOrder(domain, new Plan(o.planId().toString(), o.amountVnd(), o.planMonths(), Math.max(1, items.size())), PLAN_ZONE);
        if (!repo.confirm(o.code(), now, ref)) {
            throw BillingProblems.orderNotPending();
        }
        for (Entitlement e : granted) {
            repo.insertEntitlement(o.userId(), UUID.fromString(e.courseId()), e.validFrom(), e.validUntil(), o.id());
        }
        Map<String, Object> detail = baseDetail(o, items.values());
        if (ref != null) {
            detail.put("bankRef", ref);
        }
        audit.record("order.confirm", "order", o.id(), detail);
        events.publishEvent(new OrderConfirmed(o.id(), o.userId(), List.copyOf(items.keySet()), now));
        return view(repo.find(o.code()).orElseThrow(), List.copyOf(items.values()), users.findById(o.userId()));
    }

    @Transactional
    AdminOrderView reject(String code, String reason) {
        authz.require(Role.SYS_ADMIN, Optional.empty());
        String text = reason == null ? "" : reason.strip();
        if (text.isEmpty()) {
            throw BillingProblems.reasonRequired("A reason is required to reject an order.");
        }
        if (text.length() > MAX_REASON) {
            throw BillingProblems.reasonRequired("The reason must be at most " + MAX_REASON + " characters.");
        }
        AdminOrder o = load(code);
        if (!repo.reject(o.code(), text)) {
            throw BillingProblems.orderNotPending();
        }
        Map<UUID, String> items = repo.items(o.id());
        Map<String, Object> detail = baseDetail(o, items.values());
        detail.put("rejectReasonLength", text.length());
        audit.record("order.reject", "order", o.id(), detail);
        events.publishEvent(new OrderRejected(o.id(), o.userId(), text));
        return view(repo.find(o.code()).orElseThrow(), List.copyOf(items.values()), users.findById(o.userId()));
    }

    private AdminOrder load(String code) {
        if (!Codes.isValidOrderCode(code)) {
            throw BillingProblems.notFound();
        }
        AdminOrder o = repo.find(code).orElseThrow(BillingProblems::notFound);
        if (!OrderStatus.PENDING_PAYMENT.name().equals(o.status())) {
            throw BillingProblems.orderNotPending();
        }
        return o;
    }

    /** What an audit record of a decision may hold: codes, the amount and the length; no account number, no free text. */
    private static Map<String, Object> baseDetail(AdminOrder o, java.util.Collection<String> courseCodes) {
        Map<String, Object> d = new LinkedHashMap<>();
        d.put("orderCode", o.code());
        d.put("amountVnd", o.amountVnd());
        d.put("planMonths", o.planMonths());
        d.put("courseCodes", List.copyOf(courseCodes));
        return d;
    }

    private AdminOrderView view(AdminOrder o, List<String> courseCodes, Optional<UserView> who) {
        String note = TransferNote.build(o.code(), courseCodes, props.transferNoteMaxLength());
        return new AdminOrderView(o.code(), o.status(), o.amountVnd(), o.planMonths(), o.createdAt(), o.confirmedAt(), o.bankRef(), o.rejectReason(), note, o.userId(),
                who.map(UserView::email).orElse(null), who.map(UserView::displayName).orElse(null), courseCodes);
    }
}
