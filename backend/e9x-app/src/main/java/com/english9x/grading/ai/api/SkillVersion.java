package com.english9x.grading.ai.api;

import com.fasterxml.jackson.databind.JsonNode;
import java.util.UUID;

/**
 * The part of a grading-skill version the model needs. {@code criteria}: array of {@code {code, name, min, max, step}}; {@code resultSchema}: JSON Schema (2020-12)
 * the answer must match, with one numeric property per criterion code; {@code promptTemplate}: Mustache with {@code {{rubric}} {{samples}} {{task}} {{response}}}.
 */
public record SkillVersion(UUID versionId, String rubric, JsonNode criteria, String promptTemplate, JsonNode samples, JsonNode resultSchema) {}
