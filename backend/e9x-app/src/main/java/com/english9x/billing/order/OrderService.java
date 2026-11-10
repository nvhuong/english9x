package com.english9x.billing.order;

import com.english9x.audit.api.AuditLog;
import com.english9x.billing.internal.BillingProblems;
import com.english9x.billing.internal.BillingProperties;
import com.english9x.billing.order.OrderDtos.CreateOrderRequest;
import com.english9x.billing.order.OrderDtos.OrderCourse;
import com.english9x.billing.order.OrderDtos.OrderCreated;
import com.english9x.billing.order.OrderDtos.OrderView;
import com.english9x.billing.order.OrderRepository.OrderRow;
import com.english9x.billing.order.OrderRepository.PlanRow;
import com.english9x.domain.billing.Codes;
import com.english9x.domain.billing.Plan;
import com.english9x.domain.billing.PlanSelection;
import com.english9x.domain.billing.TransferNote;
import com.english9x.identity.api.UserDirectory;
import com.english9x.org.api.CurrentContextHolder;
import java.time.Clock;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Placing and reading orders (spec section 11). An order is PENDING_PAYMENT and carries the price and the length of the plan AS THEY WERE when it
 * was placed. Confirming it (P5.2) is not here.
 */
@Service
class OrderService {

    /** Tries before giving up on finding a free code: with 32^6 codes a second try is already rare, twenty means the table is nearly full. */
    private static final int CODE_ATTEMPTS = 20;

    private final OrderRepository repo;
    private final UserDirectory users;
    private final AuditLog audit;
    private final OrderCodes codes;
    private final BillingProperties props;
    private final Clock clock;

    OrderService(OrderRepository repo, UserDirectory users, AuditLog audit, OrderCodes codes, BillingProperties props, Clock clock) {
        this.repo = repo;
        this.users = users;
        this.audit = audit;
        this.codes = codes;
        this.props = props;
        this.clock = clock;
    }

    private static UUID actingUser() {
        return CurrentContextHolder.current().orElseThrow(() -> new AccessDeniedException("no user")).userId();
    }

    /**
     * @throws com.english9x.identity.api.EmailNotVerifiedException (403) when the person has not verified their email: the first check, so nothing
     *         else about the request is revealed to them
     */
    @Transactional
    OrderCreated create(CreateOrderRequest req) {
        UUID user = actingUser();
        users.requireVerified(user);
        PlanRow plan = repo.findPlan(req.planId()).filter(PlanRow::active).orElseThrow(BillingProblems::planNotAvailable);
        try {
            PlanSelection.validate(new Plan(plan.id().toString(), plan.priceVnd(), plan.months(), plan.maxCourses()), req.courseIds().stream().map(UUID::toString).toList());
        } catch (IllegalArgumentException e) {
            throw BillingProblems.validation("courseIds", "InvalidSelection", e.getMessage());
        }
        List<OrderCourse> courses = repo.orderableCourses(req.courseIds());
        if (courses.size() != req.courseIds().size()) {
            throw BillingProblems.validation("courseIds", "CourseNotAvailable", "every course must be a published English9x course");
        }
        UUID orderId = UUID.randomUUID();
        String code = null;
        for (int attempt = 0; attempt < CODE_ATTEMPTS && code == null; attempt++) {
            String candidate = codes.next();
            if (!Codes.isValidOrderCode(candidate)) {
                throw new IllegalStateException("order code generator produced an invalid code");
            }
            if (repo.insertOrder(orderId, candidate, user, plan.id(), plan.months(), plan.priceVnd(), clock.instant())) {
                code = candidate;
            }
        }
        if (code == null) {
            throw new IllegalStateException("could not find a free order code in " + CODE_ATTEMPTS + " tries");
        }
        for (OrderCourse c : courses) {
            repo.insertItem(orderId, user, c);
        }
        Map<String, Object> detail = new LinkedHashMap<>();
        detail.put("planId", plan.id().toString());
        detail.put("orderCode", code);
        detail.put("amountVnd", plan.priceVnd());
        detail.put("courses", courses.size());
        audit.record("order.create", "order", orderId, detail);
        return new OrderCreated(code, plan.priceVnd(), transferNote(code, courses), null, null);
    }

    @Transactional(readOnly = true)
    List<OrderView> mine() {
        UUID user = actingUser();
        return repo.mine(user).stream().map(o -> view(o, repo.itemsOf(o.id(), user))).toList();
    }

    @Transactional(readOnly = true)
    OrderView mine(String code) {
        UUID user = actingUser();
        if (!Codes.isValidOrderCode(code)) {
            throw BillingProblems.notFound();
        }
        OrderRow o = repo.mine(user, code).orElseThrow(BillingProblems::notFound);
        return view(o, repo.itemsOf(o.id(), user));
    }

    private OrderView view(OrderRow o, List<OrderCourse> courses) {
        return new OrderView(o.code(), o.status(), o.amountVnd(), o.planId(), o.planMonths(), o.createdAt(), transferNote(o.code(), courses), courses, o.rejectReason());
    }

    private String transferNote(String code, List<OrderCourse> courses) {
        return TransferNote.build(code, courses.stream().map(OrderCourse::code).toList(), props.transferNoteMaxLength());
    }
}
