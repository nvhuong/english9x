package com.english9x.org.internal;

import com.english9x.org.api.Role;
import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

/** Request / response bodies of the organisation endpoints. */
final class OrgDtos {

    private OrgDtos() {}

    @Schema(name = "OrgType", enumAsRef = true)
    enum OrgType { CENTER, ENTERPRISE }

    /** One role, for one exam when the role is per exam (AUTHOR / REVIEWER / TEACHER). */
    @Schema(name = "RoleSpec")
    record RoleSpec(
            @NotNull @Schema(requiredMode = Schema.RequiredMode.REQUIRED) Role role,
            @Schema(nullable = true, description = "IELTS | TOEIC; required for AUTHOR, REVIEWER, TEACHER, absent for the others") @Size(max = 32) String examCode) {}

    @Schema(name = "OrgView")
    record OrgView(
            @Schema(requiredMode = Schema.RequiredMode.REQUIRED) UUID id,
            @Schema(requiredMode = Schema.RequiredMode.REQUIRED) String name,
            @Schema(requiredMode = Schema.RequiredMode.REQUIRED) OrgType type) {}

    @Schema(name = "AdminOrgView")
    record AdminOrgView(
            @Schema(requiredMode = Schema.RequiredMode.REQUIRED) UUID id,
            @Schema(requiredMode = Schema.RequiredMode.REQUIRED) String name,
            @Schema(requiredMode = Schema.RequiredMode.REQUIRED) OrgType type,
            @Schema(requiredMode = Schema.RequiredMode.REQUIRED) Instant createdAt) {}

    @Schema(name = "CreateOrganizationRequest")
    record CreateOrganizationRequest(
            @NotBlank @Size(max = 120) String name,
            @NotNull OrgType type) {}

    @Schema(name = "GrantView")
    record GrantView(
            @Schema(requiredMode = Schema.RequiredMode.REQUIRED) Role role,
            @Schema(nullable = true, description = "null = English9x-wide") UUID orgId,
            @Schema(nullable = true, description = "null = every exam") String examCode) {}

    @Schema(name = "MeContext")
    record MeContext(
            @Schema(nullable = true, description = "The organisation the server applied to THIS request (null = personal)") UUID contextOrgId,
            @Schema(requiredMode = Schema.RequiredMode.REQUIRED) List<OrgView> organizations,
            @Schema(requiredMode = Schema.RequiredMode.REQUIRED) List<GrantView> grants) {}

    @Schema(name = "InviteRequest")
    record InviteRequest(
            @NotBlank @Email @Size(max = 254) String email,
            @NotEmpty @Size(max = 20) List<@Valid @NotNull RoleSpec> roles) {}

    @Schema(name = "InvitationStatus", enumAsRef = true)
    enum InvitationStatus { PENDING, ACCEPTED, REVOKED, EXPIRED }

    @Schema(name = "InvitationView")
    record InvitationView(
            @Schema(requiredMode = Schema.RequiredMode.REQUIRED) UUID id,
            @Schema(requiredMode = Schema.RequiredMode.REQUIRED) String email,
            @Schema(requiredMode = Schema.RequiredMode.REQUIRED) List<RoleSpec> roles,
            @Schema(requiredMode = Schema.RequiredMode.REQUIRED) InvitationStatus status,
            @Schema(requiredMode = Schema.RequiredMode.REQUIRED) Instant createdAt,
            @Schema(requiredMode = Schema.RequiredMode.REQUIRED) Instant expiresAt) {}

    @Schema(name = "AcceptInvitationResponse")
    record AcceptInvitationResponse(
            @Schema(example = "accepted", requiredMode = Schema.RequiredMode.REQUIRED) String status,
            @Schema(nullable = true, description = "The organisation joined, null for an English9x invitation") UUID orgId) {}

    @Schema(name = "SetRolesRequest")
    record SetRolesRequest(
            @NotNull UUID userId,
            @NotNull @Size(max = 20) List<@Valid @NotNull RoleSpec> roles) {}

    @Schema(name = "MemberView")
    record MemberView(
            @Schema(requiredMode = Schema.RequiredMode.REQUIRED) UUID userId,
            @Schema(requiredMode = Schema.RequiredMode.REQUIRED) String email,
            @Schema(nullable = true) String displayName,
            @Schema(requiredMode = Schema.RequiredMode.REQUIRED) List<RoleSpec> roles) {}

    @Schema(name = "MembersView")
    record MembersView(@Schema(requiredMode = Schema.RequiredMode.REQUIRED) List<MemberView> members) {}
}
