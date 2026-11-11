package com.english9x.classroom.klass;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

/** Bodies of the class endpoints. */
public final class ClassDtos {

    private ClassDtos() {}

    @Schema(name = "CreateClassRequest")
    public record CreateClassRequest(
            @NotBlank @Size(max = 100) String name,
            @Schema(nullable = true, description = "required for a centre's class, not allowed for an enterprise's group") UUID teacherId) {}

    @Schema(name = "ClassView")
    public record ClassView(
            @Schema(requiredMode = Schema.RequiredMode.REQUIRED) UUID id,
            @Schema(requiredMode = Schema.RequiredMode.REQUIRED) String name,
            @Schema(requiredMode = Schema.RequiredMode.REQUIRED, allowableValues = {"CLASS", "GROUP"}) String kind,
            @Schema(nullable = true) UUID teacherId,
            @Schema(requiredMode = Schema.RequiredMode.REQUIRED, description = "the caller is the ORG_ADMIN or the teacher of this class") boolean canManage,
            @Schema(nullable = true, description = "only for those who manage the class") Integer memberCount) {}

    @Schema(name = "ClassMemberView")
    public record ClassMemberView(
            @Schema(requiredMode = Schema.RequiredMode.REQUIRED) UUID userId,
            @Schema(nullable = true) String displayName,
            @Schema(nullable = true, description = "only the ORG_ADMIN reads addresses") String email,
            @Schema(requiredMode = Schema.RequiredMode.REQUIRED) Instant addedAt) {}

    @Schema(name = "AddClassMembersRequest")
    public record AddClassMembersRequest(@NotEmpty @Size(max = 200) List<@NotNull UUID> userIds) {}

    @Schema(name = "AddClassMembersResponse")
    public record AddClassMembersResponse(@Schema(requiredMode = Schema.RequiredMode.REQUIRED) int added) {}

    @Schema(name = "AssignClassCoursesRequest")
    public record AssignClassCoursesRequest(@NotEmpty @Size(max = 50) List<@NotNull UUID> courseIds, @NotNull UUID licenseId) {}

    @Schema(name = "AssignClassCoursesResponse")
    public record AssignClassCoursesResponse(
            @Schema(requiredMode = Schema.RequiredMode.REQUIRED, description = "members who were given a seat now") int assigned,
            @Schema(requiredMode = Schema.RequiredMode.REQUIRED, description = "members who already held a seat of this licence (left as they were)") List<UUID> alreadyHolding) {}
}
