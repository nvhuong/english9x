package com.english9x.billing.plan;

import com.english9x.billing.plan.PlanDtos.PlanRequest;
import com.english9x.billing.plan.PlanDtos.PlanView;
import jakarta.validation.Valid;
import java.util.List;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

/** Plans on sale for learners, and the price list for the English9x SYS_ADMIN. */
@RestController
class PlanController {

    private final PlanService plans;

    PlanController(PlanService plans) {
        this.plans = plans;
    }

    /** Plans on sale, cheapest first. */
    @GetMapping("/api/plans")
    List<PlanView> onSale() {
        return plans.onSale();
    }

    /** Every plan, on sale or not. SYS_ADMIN only. */
    @GetMapping("/api/admin/plans")
    List<PlanView> all() {
        return plans.all();
    }

    @PostMapping("/api/admin/plans")
    @ResponseStatus(HttpStatus.CREATED)
    PlanView create(@Valid @RequestBody PlanRequest body) {
        return plans.create(body);
    }

    /** Replaces the plan's fields. Orders already placed keep the price and the length they locked. */
    @PutMapping("/api/admin/plans/{id}")
    PlanView update(@PathVariable UUID id, @Valid @RequestBody PlanRequest body) {
        return plans.update(id, body);
    }
}
