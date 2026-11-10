package com.english9x.content.question.api;

import com.fasterxml.jackson.databind.JsonNode;
import java.util.List;
import java.util.UUID;

/**
 * The learner's side of a published group version: the shared stimulus (title, instructions, passage, audio, image) and the member question
 * versions, in order. No transcript, no notes (the solution side of the version).
 */
public record QuestionGroupExamView(UUID versionId, UUID groupId, String examCode, JsonNode content, List<UUID> questionVersionIds) {}
