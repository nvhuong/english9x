package com.english9x.exam.internal;

import com.english9x.content.test.api.TestExamView;
import com.fasterxml.jackson.databind.JsonNode;
import io.swagger.v3.oas.annotations.media.Schema;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

/**
 * What the exam room's API says. There is deliberately no field for a key, a transcript, a sample answer or an explanation, in these records or in the
 * content they carry ({@link TestExamView} is built from the exam views of the question module). What the learner wrote is called {@code value}.
 */
final class ExamDtos {

    private ExamDtos() {}

    @Schema(name = "ExamStartRequest")
    record StartRequest(@Schema(description = "a PUBLISHED test version") UUID testVersionId) {}

    @Schema(name = "ExamSaveRequest")
    record SaveRequest(@Schema(description = "what the learner chose or wrote: any JSON value; its form is the question type's") JsonNode answer,
                       @Schema(description = "when the learner wrote it, on the server's clock as the client estimates it (offline answers: when it was written)") Instant answeredAt) {}

    @Schema(name = "ExamSectionSummary")
    record SectionSummary(String sectionId, int position, @Schema(nullable = true) String skill, int durationSec,
                          @Schema(description = "DONE, CURRENT or PENDING") String state) {}

    @Schema(name = "ExamSectionContent")
    record SectionContent(String sectionId, @Schema(nullable = true) String skill, int durationSec, Instant startsAt, Instant endsAt, List<TestExamView.Group> groups) {}

    @Schema(name = "ExamSavedResponse")
    record SavedResponse(UUID questionId, JsonNode value, Instant savedAt) {}

    @Schema(name = "ExamResult")
    record Result(UUID attemptId, String status, Instant submittedAt, String reason, boolean interrupted) {}

    @Schema(name = "ExamAttempt")
    record AttemptView(UUID attemptId, UUID testVersionId, String title, String examCode, @Schema(description = "IN_PROGRESS or SUBMITTED") String status,
                       List<SectionSummary> sections,
                       @Schema(description = "the section being sat, with its content; null once submitted. Later sections are not sent before their turn", nullable = true)
                       SectionContent currentSection,
                       @Schema(description = "end of the current section", nullable = true) Instant endsAt, Instant serverNow, int resumeCount, int maxResumes,
                       @Schema(description = "what is saved for the current section's questions") List<SavedResponse> saved,
                       @Schema(description = "set once submitted", nullable = true) Result result) {}

    @Schema(name = "ExamAudioLink")
    record AudioLink(String url, Instant expiresAt) {}
}
