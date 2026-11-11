package com.english9x.learning.internal;

import com.english9x.domain.scoring.Answer;
import com.english9x.domain.scoring.AnswerKey;
import com.fasterxml.jackson.databind.JsonNode;
import java.util.ArrayList;
import java.util.List;

/**
 * Turns the JSON of an answer and of an answer key ({@code {"slots": [[accepted variants]...], "maxWords": n}}) into the domain's types, as the automatic grader does.
 * A learner's answer is read leniently (a wrong shape scores as wrong); a key the domain refuses is a data error for the author to fix, never a free point.
 */
final class AnswerMapper {

    private AnswerMapper() {}

    static Answer answer(JsonNode node) {
        if (node == null || node.isNull() || node.isMissingNode()) {
            return Answer.empty();
        }
        if (node.isObject() && node.path("values").isArray()) {
            return answer(node.get("values"));
        }
        if (node.isArray()) {
            List<String> values = new ArrayList<>();
            for (JsonNode element : node) {
                values.add(scalar(element));
            }
            return new Answer(values);
        }
        String one = scalar(node);
        return one.isEmpty() ? Answer.empty() : Answer.of(one);
    }

    private static String scalar(JsonNode node) {
        return node != null && (node.isTextual() || node.isNumber() || node.isBoolean()) ? node.asText() : "";
    }

    static AnswerKey key(JsonNode key, double points, Object versionId) {
        try {
            List<List<String>> slots = new ArrayList<>();
            JsonNode raw = key == null ? null : key.path("slots");
            if (raw == null || !raw.isArray()) {
                throw new IllegalArgumentException("slots missing");
            }
            for (JsonNode slot : raw) {
                List<String> variants = new ArrayList<>();
                for (JsonNode v : slot) {
                    variants.add(v.asText());
                }
                slots.add(variants);
            }
            JsonNode max = key.path("maxWords");
            return new AnswerKey(slots, max.isInt() ? max.asInt() : 0, points);
        } catch (IllegalArgumentException e) {
            throw new IllegalStateException("question version " + versionId + " has an answer key the scorer cannot read: " + e.getMessage(), e);
        }
    }

    /** The key as a person reads it: the first accepted variant of each slot. */
    static String display(JsonNode key) {
        List<String> parts = new ArrayList<>();
        for (JsonNode slot : key.path("slots")) {
            if (slot.size() > 0) {
                parts.add(slot.get(0).asText());
            }
        }
        return String.join(", ", parts);
    }
}
