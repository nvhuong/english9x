package com.english9x.catalog.api;

import com.english9x.domain.scoring.InteractionKind;
import com.networknt.schema.JsonSchema;
import java.util.Optional;

/**
 * A question type of an exam: its place in the exam ({@code examCode}, {@code section}), how a learner answers
 * ({@link #kind()}), the JSON Schema of the question's content and of its answer key, and the Excel template that imports it.
 * The answer key schema describes the shape {@code ObjectiveScorers} reads (slots of accepted variants, an optional word limit).
 */
public record QuestionTypeDef(
        String code,
        String examCode,
        String section,
        InteractionKind kind,
        JsonSchema contentSchema,
        JsonSchema answerKeySchema,
        Optional<String> excelTemplateId) {}
