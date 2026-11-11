package com.english9x.classroom.assignment;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotNull;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

/** Bodies of the assignment endpoints. */
public final class AssignmentDtos {

    private AssignmentDtos() {}

    @Schema(name = "CreateAssignmentRequest", description = "exactly one of lessonId (a lesson, chapter test or mock test of a course) and testVersionId (an exam)")
    public record CreateAssignmentRequest(@Schema(nullable = true) UUID lessonId, @Schema(nullable = true) UUID testVersionId, @NotNull Instant dueAt) {}

    @Schema(name = "AssignmentStatus", enumAsRef = true, description = "SUBMITTED in time, LATE (handed in after dueAt), MISSING (not handed in, dueAt has passed), PENDING (not yet, not yet due)")
    public enum Status { PENDING, SUBMITTED, LATE, MISSING }

    @Schema(name = "AssignmentView")
    public record AssignmentView(
            @Schema(requiredMode = Schema.RequiredMode.REQUIRED) UUID id,
            @Schema(nullable = true) UUID lessonId,
            @Schema(nullable = true) UUID testVersionId,
            @Schema(nullable = true) String title,
            @Schema(requiredMode = Schema.RequiredMode.REQUIRED) Instant dueAt,
            @Schema(requiredMode = Schema.RequiredMode.REQUIRED) Instant createdAt,
            @Schema(requiredMode = Schema.RequiredMode.REQUIRED) int total,
            @Schema(requiredMode = Schema.RequiredMode.REQUIRED) int submitted,
            @Schema(requiredMode = Schema.RequiredMode.REQUIRED) int late,
            @Schema(requiredMode = Schema.RequiredMode.REQUIRED) int missing) {}

    @Schema(name = "AssignmentReportRow")
    public record ReportRow(
            @Schema(requiredMode = Schema.RequiredMode.REQUIRED) UUID userId,
            @Schema(nullable = true) String displayName,
            @Schema(nullable = true, description = "only the ORG_ADMIN reads addresses") String email,
            @Schema(requiredMode = Schema.RequiredMode.REQUIRED) Status status,
            @Schema(nullable = true) Instant submittedAt) {}

    @Schema(name = "AssignmentReport")
    public record Report(@Schema(requiredMode = Schema.RequiredMode.REQUIRED) UUID assignmentId, @Schema(requiredMode = Schema.RequiredMode.REQUIRED) List<ReportRow> rows) {}

    @Schema(name = "MyAssignment")
    public record MyAssignment(
            @Schema(requiredMode = Schema.RequiredMode.REQUIRED) UUID id,
            @Schema(requiredMode = Schema.RequiredMode.REQUIRED) UUID classId,
            @Schema(requiredMode = Schema.RequiredMode.REQUIRED) String className,
            @Schema(nullable = true) UUID lessonId,
            @Schema(nullable = true) UUID courseId,
            @Schema(nullable = true) UUID testVersionId,
            @Schema(nullable = true) String title,
            @Schema(requiredMode = Schema.RequiredMode.REQUIRED) Instant dueAt,
            @Schema(requiredMode = Schema.RequiredMode.REQUIRED) Status status,
            @Schema(nullable = true) Instant submittedAt) {}
}
