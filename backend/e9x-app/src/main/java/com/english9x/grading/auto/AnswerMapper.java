package com.english9x.grading.auto;

import com.english9x.domain.scoring.Answer;
import com.english9x.domain.scoring.AnswerKey;
import com.fasterxml.jackson.databind.JsonNode;
import java.util.ArrayList;
import java.util.List;

/**
 * Turns the two JSON documents of a question into the domain's types: what the learner wrote ({@code responses.answer}, "any JSON value; its form is the
 * question type's") and the key ({@code question_versions.answer_key}: {@code {"slots": [[accepted variants]...], "maxWords": n}}).
 *
 * <p>An answer is read leniently, because the learner's client is not trusted to send one exact shape and a wrong shape must score as wrong, not fail the
 * whole attempt: an array gives one value per element (a number or boolean is its text, anything else is blank), a string or number is one value,
 * {@code {"values": [...]}} is its array, and null or anything else is no answer. A key is read strictly: a key the domain refuses is a data error that
 * stops the scoring (the question's author is the one to fix it), never a silent zero.
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

    /** @throws IllegalStateException when the key is not one the domain accepts; the message names the version, never the key */
    static AnswerKey key(JsonNode key, double points, Object versionId) {
        try {
            List<List<String>> slots = new ArrayList<>();
            JsonNode raw = key == null ? null : key.path("slots");
            if (raw == null || !raw.isArray()) {
                throw new IllegalArgumentException("slots missing");
            }
            for (JsonNode slot : raw) {
                if (!slot.isArray()) {
                    throw new IllegalArgumentException("a slot is not a list");
                }
                List<String> variants = new ArrayList<>();
                for (JsonNode v : slot) {
                    if (!v.isTextual()) {
                        throw new IllegalArgumentException("a variant is not text");
                    }
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
}
