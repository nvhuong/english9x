package com.english9x.classroom.attendance;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

/** Bodies of the attendance endpoints. */
public final class AttendanceDtos {

    private AttendanceDtos() {}

    @Schema(name = "AttendanceStatus", enumAsRef = true, description = "PRESENT, ABSENT, LATE (came late: counts as attended) or EXCUSED (leaves the rate)")
    public enum Status { PRESENT, ABSENT, LATE, EXCUSED }

    @Schema(name = "AttendanceMark")
    public record Mark(@NotNull UUID userId, @NotNull Status status, @Schema(nullable = true) @Size(max = 500) String note) {}

    @Schema(name = "AttendanceRow")
    public record Row(
            @Schema(requiredMode = Schema.RequiredMode.REQUIRED) UUID userId,
            @Schema(nullable = true) String displayName,
            @Schema(nullable = true, description = "only the ORG_ADMIN reads addresses") String email,
            @Schema(nullable = true, description = "null: not marked yet") Status status,
            @Schema(nullable = true) String note,
            @Schema(nullable = true) Instant markedAt) {}

    @Schema(name = "AttendanceSheet")
    public record Sheet(@Schema(requiredMode = Schema.RequiredMode.REQUIRED) UUID sessionId, @Schema(requiredMode = Schema.RequiredMode.REQUIRED) List<Row> rows) {}

    @Schema(name = "AttendanceReportRow", description = "rate = (present + late) / (present + late + absent); excused sessions are left out; null while nothing is marked")
    public record ReportRow(
            @Schema(requiredMode = Schema.RequiredMode.REQUIRED) UUID userId,
            @Schema(nullable = true) String displayName,
            @Schema(nullable = true, description = "only the ORG_ADMIN reads addresses") String email,
            @Schema(requiredMode = Schema.RequiredMode.REQUIRED) int present,
            @Schema(requiredMode = Schema.RequiredMode.REQUIRED) int late,
            @Schema(requiredMode = Schema.RequiredMode.REQUIRED) int absent,
            @Schema(requiredMode = Schema.RequiredMode.REQUIRED) int excused,
            @Schema(nullable = true, description = "0..1") Double rate) {}

    @Schema(name = "AttendanceReport")
    public record Report(@Schema(requiredMode = Schema.RequiredMode.REQUIRED) UUID classId, @Schema(requiredMode = Schema.RequiredMode.REQUIRED) List<ReportRow> rows) {}
}
