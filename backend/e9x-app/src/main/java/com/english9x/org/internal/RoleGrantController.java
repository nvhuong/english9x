package com.english9x.org.internal;

import com.english9x.org.internal.OrgDtos.MemberView;
import com.english9x.org.internal.OrgDtos.MembersView;
import com.english9x.org.internal.OrgDtos.SetRolesRequest;
import jakarta.validation.Valid;
import java.util.UUID;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;

@RestController
class RoleGrantController {

    private final RoleGrantService grants;

    RoleGrantController(RoleGrantService grants) {
        this.grants = grants;
    }

    @GetMapping("/api/orgs/{id}/role-grants")
    MembersView list(@PathVariable UUID id) {
        return grants.list(id);
    }

    /** Replaces the roles of one member of the organisation; the member must already belong to it. */
    @PutMapping("/api/orgs/{id}/role-grants")
    MemberView set(@PathVariable UUID id, @Valid @RequestBody SetRolesRequest body) {
        return grants.set(id, body);
    }
}
