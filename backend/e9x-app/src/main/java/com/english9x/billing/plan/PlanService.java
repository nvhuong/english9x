package com.english9x.billing.plan;

import com.english9x.audit.api.AuditLog;
import com.english9x.billing.internal.BillingProblems;
import com.english9x.billing.plan.PlanDtos.PlanRequest;
import com.english9x.billing.plan.PlanDtos.PlanView;
import com.english9x.org.api.Authz;
import com.english9x.org.api.Role;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** The price list (spec section 11). Reading is for everybody signed in (active plans); writing is a real SYS_ADMIN's, and audited. */
@Service
class PlanService {

    private final PlanRepository repo;
    private final Authz authz;
    private final AuditLog audit;

    PlanService(PlanRepository repo, Authz authz, AuditLog audit) {
        this.repo = repo;
        this.authz = authz;
        this.audit = audit;
    }

    @Transactional(readOnly = true)
    List<PlanView> onSale() {
        return repo.listActive();
    }

    @Transactional(readOnly = true)
    List<PlanView> all() {
        authz.require(Role.SYS_ADMIN, Optional.empty());
        return repo.listAll();
    }

    @Transactional
    PlanView create(PlanRequest r) {
        authz.require(Role.SYS_ADMIN, Optional.empty());
        PlanView created = repo.insert(r);
        audit.record("plan.create", "plan", created.id(), detail(created));
        return created;
    }

    @Transactional
    PlanView update(UUID id, PlanRequest r) {
        authz.require(Role.SYS_ADMIN, Optional.empty());
        PlanView before = repo.find(id).orElseThrow(BillingProblems::notFound);
        PlanView after = repo.update(id, r).orElseThrow(BillingProblems::notFound);
        Map<String, Object> d = detail(after);
        d.put("previousPriceVnd", before.priceVnd());
        d.put("previousActive", before.active());
        audit.record("plan.update", "plan", id, d);
        return after;
    }

    private static Map<String, Object> detail(PlanView p) {
        Map<String, Object> d = new LinkedHashMap<>();
        d.put("name", p.name());
        d.put("priceVnd", p.priceVnd());
        d.put("months", p.months());
        d.put("maxCourses", p.maxCourses());
        d.put("active", p.active());
        return d;
    }
}
