package com.english9x.grading.teacher;

import com.fasterxml.jackson.databind.JsonNode;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/** The JSON of the teachers' queue. Never the learner's identity, the cost or the token counts of the AI. */
final class TeacherDtos {

    private TeacherDtos() {}

    /** One line of a teacher's list: {@code mine} when they hold it. */
    record QueueItemView(UUID id, String examCode, String state, boolean mine, Instant createdAt, Instant claimDueAt) {}

    /** One line of the administrator's list of their side. */
    record AdminQueueItemView(UUID id, String examCode, String state, String reason, String side, Instant createdAt, UUID claimedBy, String claimedByName, Instant claimDueAt) {}

    record CriterionView(String code, String name, Double min, Double max, Double step) {}

    record AiView(double band, JsonNode result) {}

    record TaskView(UUID questionId, String typeCode, String prompt, String text, boolean hasRecording, AiView ai) {}

    record ReviewView(Map<String, Double> criteria, String comment, double band) {}

    /** One skill of the attempt (Writing, Speaking): what the learner did, what the AI said, the criteria to score, the review once given. */
    record SkillView(String skill, List<TaskView> tasks, List<CriterionView> criteria, Double aiBand, ReviewView review) {}

    record Detail(UUID id, String examCode, String state, Instant claimDueAt, List<SkillView> skills) {}

    record ReviewRequest(String skill, Map<String, Double> criteria, String comment) {}

    record ReviewResult(String skill, double band, boolean done, List<String> remaining) {}

    record ReassignRequest(UUID teacherId) {}
}
