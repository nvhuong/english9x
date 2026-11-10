package com.english9x.content.strategy.api;

import com.fasterxml.jackson.databind.JsonNode;
import java.util.List;
import java.util.UUID;

/** A lesson as a learner reads it. {@code blocks} name an embedded question by {@code questionVersionId}; read it with {@code QuestionVersions#examView}. */
public record StrategyLessonExamView(UUID versionId, UUID lessonId, String examCode, String title, JsonNode blocks, List<String> questionTypeCodes) {}
