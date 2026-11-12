package com.english9x.analytics.reports;

import io.swagger.v3.oas.annotations.media.Schema;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

/** The JSON of the class report (F49). The .xlsx holds exactly these figures. */
final class ReportsDtos {

    private ReportsDtos() {}

    @Schema(name = "ReportCourse", description = "One course the learner holds a seat of in this organisation, with the share of its lessons and chapter tests done")
    public record CourseLine(@Schema(requiredMode = Schema.RequiredMode.REQUIRED) UUID courseId, @Schema(nullable = true) String code,
            @Schema(requiredMode = Schema.RequiredMode.REQUIRED) String title, @Schema(requiredMode = Schema.RequiredMode.REQUIRED) int percent) {}

    @Schema(name = "ReportScore", description = "A displayed score; kind is PRELIMINARY or OFFICIAL, the label of the grading module")
    public record ScoreLine(@Schema(requiredMode = Schema.RequiredMode.REQUIRED) double value, @Schema(requiredMode = Schema.RequiredMode.REQUIRED, allowableValues = {"PRELIMINARY", "OFFICIAL"}) String kind,
            @Schema(requiredMode = Schema.RequiredMode.REQUIRED) String examCode) {}

    @Schema(name = "ReportAssignments", description = "The class's assignments for this learner: handed in on time, late, not handed in after the due date (missing), not yet due (pending)")
    public record Assignments(@Schema(requiredMode = Schema.RequiredMode.REQUIRED) int total, @Schema(requiredMode = Schema.RequiredMode.REQUIRED) int submitted,
            @Schema(requiredMode = Schema.RequiredMode.REQUIRED) int late, @Schema(requiredMode = Schema.RequiredMode.REQUIRED) int missing,
            @Schema(requiredMode = Schema.RequiredMode.REQUIRED) int pending) {}

    @Schema(name = "ReportAttendance", description = "Marked sessions; rate = (present + late) / (present + late + absent), excused sessions leave the rate; null when there is nothing to rate")
    public record Attendance(@Schema(requiredMode = Schema.RequiredMode.REQUIRED) int present, @Schema(requiredMode = Schema.RequiredMode.REQUIRED) int late,
            @Schema(requiredMode = Schema.RequiredMode.REQUIRED) int absent, @Schema(requiredMode = Schema.RequiredMode.REQUIRED) int excused, @Schema(nullable = true) Double rate) {}

    @Schema(name = "ReportWeakType", description = "A question type the learner gets wrong (rate < 1), worst first; rate = correct / total over the attempts made in this organisation")
    public record WeakType(@Schema(requiredMode = Schema.RequiredMode.REQUIRED) String typeCode, @Schema(requiredMode = Schema.RequiredMode.REQUIRED) String typeName,
            @Schema(requiredMode = Schema.RequiredMode.REQUIRED) int correct, @Schema(requiredMode = Schema.RequiredMode.REQUIRED) int total,
            @Schema(requiredMode = Schema.RequiredMode.REQUIRED) double rate) {}

    @Schema(name = "ReportLearner")
    public record LearnerRow(
            @Schema(requiredMode = Schema.RequiredMode.REQUIRED) UUID userId,
            @Schema(nullable = true) String displayName,
            @Schema(nullable = true, description = "only the ORG_ADMIN reads addresses") String email,
            @Schema(nullable = true, description = "mean of the courses' percents; null when the learner has no course in this organisation") Integer progressPercent,
            @Schema(requiredMode = Schema.RequiredMode.REQUIRED) List<CourseLine> courses,
            @Schema(requiredMode = Schema.RequiredMode.REQUIRED) Assignments assignments,
            @Schema(nullable = true) ScoreLine latestScore,
            @Schema(nullable = true) ScoreLine bestScore,
            @Schema(requiredMode = Schema.RequiredMode.REQUIRED) List<WeakType> weakTypes,
            @Schema(requiredMode = Schema.RequiredMode.REQUIRED) Attendance attendance,
            @Schema(requiredMode = Schema.RequiredMode.REQUIRED, description = "all study time of the person, in minutes") int studyMinutes) {}

    @Schema(name = "ClassReport")
    public record ClassReport(@Schema(requiredMode = Schema.RequiredMode.REQUIRED) UUID classId, @Schema(requiredMode = Schema.RequiredMode.REQUIRED) String className,
            @Schema(requiredMode = Schema.RequiredMode.REQUIRED, allowableValues = {"CLASS", "GROUP"}) String kind, @Schema(requiredMode = Schema.RequiredMode.REQUIRED) Instant generatedAt,
            @Schema(requiredMode = Schema.RequiredMode.REQUIRED) List<LearnerRow> learners) {}
}
