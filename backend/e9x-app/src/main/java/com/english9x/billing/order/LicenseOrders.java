package com.english9x.billing.order;

import com.english9x.audit.api.AuditLog;
import com.english9x.billing.api.events.OrderPlaced;
import com.english9x.billing.internal.BillingProperties;
import com.english9x.billing.order.OrderDtos.OrderCourse;
import com.english9x.billing.payment.PaymentInstructions;
import com.english9x.billing.payment.PaymentInstructions.PayTo;
import com.english9x.domain.billing.Codes;
import com.english9x.domain.billing.TransferNote;
import java.time.Clock;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/**
 * The order of a QR-activated organisation licence (P5.3), placed like any order of P5.2: PENDING_PAYMENT, an order code and a transfer note, the
 * price and the length locked on it. The SYS_ADMIN files it for the payer, an ORG_ADMIN of the organisation, who then sees it among their own
 * orders with the QR; the SYS_ADMIN confirms it in the same queue. Joins the caller's transaction (the licence row is written in it).
 */
@Service
public class LicenseOrders {

    private static final int CODE_ATTEMPTS = 20;

    /** A course of the licence, as the order records it. */
    public record Course(UUID id, String code) {}

    /** What the licence needs to show for its order. */
    public record Placed(UUID orderId, String orderCode, String transferNote, String qrPayload, String qrPngUrl, PayTo payTo) {}

    private final OrderRepository repo;
    private final OrderCodes codes;
    private final AuditLog audit;
    private final BillingProperties props;
    private final Clock clock;
    private final PaymentInstructions payment;
    private final ApplicationEventPublisher events;

    LicenseOrders(OrderRepository repo, OrderCodes codes, AuditLog audit, BillingProperties props, Clock clock, PaymentInstructions payment, ApplicationEventPublisher events) {
        this.repo = repo;
        this.codes = codes;
        this.audit = audit;
        this.props = props;
        this.clock = clock;
        this.payment = payment;
        this.events = events;
    }

    /** @param courses the licence's courses (id and code), copied onto the order as its items */
    @Transactional(propagation = Propagation.MANDATORY)
    public Placed place(UUID licenseId, UUID payerId, long amountVnd, int months, List<Course> courses) {
        UUID orderId = UUID.randomUUID();
        String code = null;
        for (int attempt = 0; attempt < CODE_ATTEMPTS && code == null; attempt++) {
            String candidate = codes.next();
            if (!Codes.isValidOrderCode(candidate)) {
                throw new IllegalStateException("order code generator produced an invalid code");
            }
            if (repo.insertLicenseOrder(orderId, candidate, payerId, licenseId, months, amountVnd, clock.instant())) {
                code = candidate;
            }
        }
        if (code == null) {
            throw new IllegalStateException("could not find a free order code in " + CODE_ATTEMPTS + " tries");
        }
        for (Course c : courses) {
            repo.insertItem(orderId, payerId, new OrderCourse(c.id(), c.code()));
        }
        Map<String, Object> detail = new LinkedHashMap<>();
        detail.put("licenseId", licenseId.toString());
        detail.put("orderCode", code);
        detail.put("amountVnd", amountVnd);
        detail.put("courses", courses.size());
        detail.put("payerId", payerId.toString());
        audit.record("order.create", "order", orderId, detail);
        events.publishEvent(new OrderPlaced(orderId, code, payerId, amountVnd));
        String note = TransferNote.build(code, courses.stream().map(Course::code).sorted().toList(), props.transferNoteMaxLength());
        Optional<PayTo> to = payment.payTo();
        final String orderCode = code;
        return new Placed(orderId, orderCode, note, to.map(t -> payment.qrPayload(t, amountVnd, note)).orElse(null),
                to.map(t -> "/api/orders/" + orderCode + "/qr.png").orElse(null), to.orElse(null));
    }
}
