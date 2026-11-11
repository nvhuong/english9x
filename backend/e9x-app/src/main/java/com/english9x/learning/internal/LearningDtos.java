package com.english9x.learning.internal;

import com.fasterxml.jackson.databind.JsonNode;
import io.swagger.v3.oas.annotations.media.Schema;
import java.math.BigDecimal;
import java.util.List;
import java.util.UUID;

/** The request and response bodies of the learning endpoints. Nothing here carries an answer key; the one answer a learner sees is the one for a practice question just answered. */
final class LearningDtos {

    private LearningDtos() {}

    @Schema(name = "LearningQuestion")
    record Question(UUID versionId, String typeCode, JsonNode content, BigDecimal points) {}

    @Schema(name = "LearningLesson")
    record LessonView(UUID lessonId, UUID courseId, String title, boolean completed, JsonNode blocks, List<String> questionTypeCodes, List<Question> questions) {}

    record PracticeRequest(UUID questionVersionId, JsonNode answer) {}

    @Schema(name = "PracticeResult")
    record PracticeResult(boolean correct, double earned, @Schema(nullable = true) String explanation, String correctAnswer) {}

    @Schema(name = "LessonCompletion")
    record Completion(boolean completed, int streakDays) {}

    @Schema(name = "ChapterTestGroup")
    record Group(JsonNode content, List<Question> questions) {}

    @Schema(name = "ChapterTestView")
    record ChapterTestView(UUID lessonId, UUID courseId, String title, double passThreshold, int maxAttempts, int attemptsUsed, boolean canRetake,
                           @Schema(nullable = true) Double bestScore, List<Group> groups) {}

    record Answered(UUID questionVersionId, JsonNode answer) {}

    record AttemptRequest(List<Answered> answers) {}

    @Schema(name = "ChapterTestResult")
    record AttemptResult(int attemptNo, double score, double raw, double rawMax, boolean passed, double bestScore, int attemptsUsed,
                         @Schema(nullable = true, description = "null = unlimited") Integer attemptsLeft) {}

    record HeartbeatRequest(UUID courseId, UUID lessonId, @Schema(nullable = true, description = "IANA zone of the browser; the days of the streak are cut in it") String timeZone) {}

    @Schema(name = "HeartbeatResult")
    record HeartbeatResult(int todaySeconds, int streakDays) {}

    @Schema(name = "MapLesson")
    record MapLesson(UUID id, String kind, String title, boolean completed, @Schema(nullable = true) Double bestScore, boolean locked) {}

    @Schema(name = "MapChapter")
    record MapChapter(String title, double passThreshold, List<MapLesson> lessons) {}

    @Schema(name = "ChapterMapView")
    record ChapterMapView(UUID courseId, String title, String unlockRule, int streakDays, int todayMinutes, List<MapChapter> chapters) {}
}
