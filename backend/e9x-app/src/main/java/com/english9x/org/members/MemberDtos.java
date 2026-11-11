package com.english9x.org.members;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

/** Bodies of the member endpoints. */
final class MemberDtos {

    private MemberDtos() {}

    @Schema(name = "BulkInviteRequest")
    record BulkInviteRequest(@NotEmpty @Size(max = 100) List<@NotNull @Email @Size(max = 254) String> emails) {}

    @Schema(name = "BulkInviteResponse")
    record BulkInviteResponse(@Schema(requiredMode = Schema.RequiredMode.REQUIRED) int invited) {}

    @Schema(name = "ImportResult")
    record ImportResult(@Schema(requiredMode = Schema.RequiredMode.REQUIRED) int rows, @Schema(requiredMode = Schema.RequiredMode.REQUIRED) int invited) {}

    @Schema(name = "CreateJoinCodeRequest")
    record CreateJoinCodeRequest(
            @Schema(nullable = true, description = "a class of the organisation the joiner is put into") UUID classId,
            @NotNull Instant expiresAt,
            @NotNull @Min(1) @Max(100000) Integer maxUses) {}

    @Schema(name = "JoinCodeStatus", enumAsRef = true)
    enum JoinCodeStatus { ACTIVE, REVOKED, EXPIRED, EXHAUSTED }

    @Schema(name = "JoinCodeView")
    record JoinCodeView(
            @Schema(requiredMode = Schema.RequiredMode.REQUIRED) UUID id,
            @Schema(nullable = true) UUID classId,
            @Schema(requiredMode = Schema.RequiredMode.REQUIRED) Instant createdAt,
            @Schema(requiredMode = Schema.RequiredMode.REQUIRED) Instant expiresAt,
            @Schema(requiredMode = Schema.RequiredMode.REQUIRED) int maxUses,
            @Schema(requiredMode = Schema.RequiredMode.REQUIRED) int usedCount,
            @Schema(requiredMode = Schema.RequiredMode.REQUIRED) JoinCodeStatus status) {}

    @Schema(name = "JoinCodeCreated", description = "The only time the code is ever shown: only its SHA-256 digest is stored")
    record JoinCodeCreated(
            @Schema(requiredMode = Schema.RequiredMode.REQUIRED) String code,
            @Schema(requiredMode = Schema.RequiredMode.REQUIRED) JoinCodeView joinCode) {}

    @Schema(name = "JoinResponse")
    record JoinResponse(
            @Schema(example = "joined", requiredMode = Schema.RequiredMode.REQUIRED, description = "joined | already-member") String status,
            @Schema(requiredMode = Schema.RequiredMode.REQUIRED) UUID orgId) {}
}
