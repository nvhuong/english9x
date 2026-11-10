package com.english9x.content.test.api;

import com.english9x.content.question.api.QuestionExamView;
import com.fasterxml.jackson.databind.JsonNode;
import java.util.List;
import java.util.UUID;

/**
 * What a learner may see of a published test version. Every component is either a plain value of the test's own structure or built from the exam views of
 * the question module ({@link QuestionExamView}: content and points only). There is deliberately no component for an answer key, a transcript, a sample
 * answer, notes or an explanation: they are the solution side of the question and group versions, and the SQL functions the views are built from do not
 * return them. Do not add one; add a separate type for the post-submit review of a result (P4.4).
 *
 * @param maxResumes how many times an attempt may be reopened; null = the exam's default ({@code e9x.exam.default-max-resumes})
 * @param variant IELTS: AC or GT; otherwise null
 */
public record TestExamView(UUID versionId, UUID testId, String examCode, String variant, String title, Integer maxResumes, List<Part> parts) {

    /** A section of the test: a TOEIC part, an IELTS skill. {@code durationSec} is the time limit the clock of the section runs on. */
    public record Part(int position, String sectionCode, String skill, int durationSec, List<Group> groups) {}

    /** A shared stimulus (title, instructions, passage, audio, image: {@code content}) and the questions asked about it, in order. */
    public record Group(UUID versionId, UUID groupId, JsonNode content, List<QuestionExamView> questions) {}
}
