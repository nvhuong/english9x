package com.english9x.grading.ai.api;

import com.fasterxml.jackson.databind.JsonNode;

/**
 * The validated answer. {@code band}: the mean of the criterion scores, rounded to the nearest half. {@code result}: the whole JSON object the model returned (it matched the
 * skill's schema).
 */
public record AiResult(JsonNode result, double band, String model, int inputTokens, int outputTokens) {}
