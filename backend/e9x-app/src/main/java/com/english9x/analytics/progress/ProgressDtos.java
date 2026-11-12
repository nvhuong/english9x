package com.english9x.analytics.progress;

import io.swagger.v3.oas.annotations.media.Schema;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;

/** The progress screen (F24): plan cards with their courses, and the series. */
final class ProgressDtos {

    private ProgressDtos() {}

    @Schema(name = "ProgressScore", description = "A score as displayed: kind is PRELIMINARY or OFFICIAL (the label of the grading module, never recomputed here)")
    record Score(double value, String kind, String source, String examCode) {}

    @Schema(name = "ProgressCourse")
    record CourseProgress(UUID courseId, String code, String title, int percent, int lessonsDone, int lessonsTotal, int chaptersDone, int chaptersTotal, int chapterTestsDone,
            int chapterTestsTotal, int examsTaken, Score latestScore, Score bestScore, int pendingAssignments,
            @Schema(nullable = true, description = "The next lesson to open (Học tiếp); null when everything is done or the plan is expired (read only)") UUID resumeLessonId,
            @Schema(nullable = true) Instant lastActivity) {}

    @Schema(name = "ProgressPlan", description = "One plan (a paid order, ORDER) or one seat of an organisation's licence (SEAT) with the courses it grants")
    record PlanCard(String source, UUID id, @Schema(nullable = true) String name, @Schema(nullable = true) String organisation, Instant activatedAt, Instant validUntil,
            boolean active, boolean readOnly, long daysLeft, int coursesUsed, @Schema(nullable = true) Integer coursesMax, List<CourseProgress> courses) {}

    @Schema(name = "ProgressScorePoint", description = "One scored attempt; predicted = mean of this score and the two before it of the same exam")
    record ScorePoint(UUID attemptId, String examCode, Instant at, double score, String kind, double predicted) {}

    @Schema(name = "ProgressWeek")
    record WeekMinutes(LocalDate weekStart, int minutes) {}

    @Schema(name = "ProgressStreak")
    record Streak(int currentDays, int longestDays, @Schema(nullable = true) LocalDate lastDay) {}

    @Schema(name = "ProgressPending")
    record PendingAssignment(UUID id, String className, Instant dueAt, boolean overdue, @Schema(nullable = true) UUID courseId) {}

    @Schema(name = "LearnerProgress")
    record Progress(List<PlanCard> plans, List<PlanCard> expired, List<ScorePoint> scoreSeries, List<WeekMinutes> weeklyMinutes, Streak streak,
            List<PendingAssignment> pendingAssignments) {}
}
