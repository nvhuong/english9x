package com.english9x.org.internal;

import com.english9x.org.api.CurrentContextHolder;
import com.english9x.org.internal.OrgDtos.AdminOrgView;
import com.english9x.org.internal.OrgDtos.CreateOrganizationRequest;
import com.english9x.org.internal.OrgDtos.MeContext;
import jakarta.validation.Valid;
import java.util.List;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

/** The person's own context, and organisation administration for English9x's SYS_ADMIN. */
@RestController
class OrgController {

    private final OrganizationService orgs;

    OrgController(OrganizationService orgs) {
        this.orgs = orgs;
    }

    /** Which organisations the person belongs to and every grant they hold: the SPA builds its switcher and role store from it. */
    @GetMapping("/api/me/context")
    MeContext me() {
        return orgs.me(CurrentContextHolder.current().flatMap(CurrentContextHolder.Binding::orgId));
    }

    @PostMapping("/api/admin/organizations")
    @ResponseStatus(HttpStatus.CREATED)
    AdminOrgView create(@Valid @RequestBody CreateOrganizationRequest body) {
        return orgs.create(body);
    }

    @GetMapping("/api/admin/organizations")
    List<AdminOrgView> list() {
        return orgs.listAll();
    }
}
