package com.english9x.org.internal;

import com.english9x.org.internal.OrgDtos.AcceptInvitationResponse;
import com.english9x.org.internal.OrgDtos.InvitationView;
import com.english9x.org.internal.OrgDtos.InviteRequest;
import jakarta.validation.Valid;
import java.util.List;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

/**
 * Invitations. English9x staff are invited under {@code /api/admin/invitations} (SYS_ADMIN), organisation members under
 * {@code /api/orgs/{id}/invitations} (that organisation's ORG_ADMIN, or a SYS_ADMIN). Accepting is open to any signed-in
 * account holding the mailed token.
 */
@RestController
class InvitationController {

    private final InvitationService invitations;

    InvitationController(InvitationService invitations) {
        this.invitations = invitations;
    }

    @PostMapping("/api/admin/invitations")
    @ResponseStatus(HttpStatus.CREATED)
    InvitationView inviteStaff(@Valid @RequestBody InviteRequest body) {
        return invitations.invite(null, body);
    }

    @GetMapping("/api/admin/invitations")
    List<InvitationView> staffInvitations() {
        return invitations.list(null);
    }

    @DeleteMapping("/api/admin/invitations/{invitationId}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    void revokeStaff(@PathVariable UUID invitationId) {
        invitations.revoke(null, invitationId);
    }

    @PostMapping("/api/orgs/{id}/invitations")
    @ResponseStatus(HttpStatus.CREATED)
    InvitationView invite(@PathVariable UUID id, @Valid @RequestBody InviteRequest body) {
        return invitations.invite(id, body);
    }

    @GetMapping("/api/orgs/{id}/invitations")
    List<InvitationView> list(@PathVariable UUID id) {
        return invitations.list(id);
    }

    @DeleteMapping("/api/orgs/{id}/invitations/{invitationId}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    void revoke(@PathVariable UUID id, @PathVariable UUID invitationId) {
        invitations.revoke(id, invitationId);
    }

    @PostMapping("/api/invitations/{token}/accept")
    AcceptInvitationResponse accept(@PathVariable String token) {
        return invitations.accept(token);
    }
}
