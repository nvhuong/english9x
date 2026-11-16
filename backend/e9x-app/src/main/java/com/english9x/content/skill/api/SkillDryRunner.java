package com.english9x.content.skill.api;

import com.fasterxml.jackson.databind.JsonNode;

/**
 * Runs a grading-skill version on a sample answer and gives back the model's answer, validated against the skill's result schema; nothing is saved and no
 * budget or limit is counted (spec 6.5: "Chạy thử" in the CMS, no score kept). The AI grading of the {@code grading} module implements it over its
 * {@code AiGrader}: {@code grading} already depends on this module (the prompt template, the staged files), so the dependency points that way and the CMS
 * only knows this port. When no implementation is present the dry run answers 501.
 */
public interface SkillDryRunner {

    /**
     * @param task what the learner was asked (the essay prompt, the speaking topic); the CMS may leave it generic
     * @throws Failure when no valid answer could be had
     */
    Result run(GradingSkillVersionView skill, String task, Answer answer);

    /** The sample answer: text (Writing) or a recording (Speaking). */
    sealed interface Answer {

        record Text(String text) implements Answer {
            @Override
            public String toString() {
                return "Text[]";
            }
        }

        record Audio(byte[] bytes, String mime) implements Answer {
            @Override
            public String toString() {
                return "Audio[" + mime + ", " + bytes.length + " bytes]";
            }
        }
    }

    /** {@code result}: the JSON object of the model, which matched the result schema; {@code band}: the mean of the criteria rounded to the nearest half. */
    record Result(JsonNode result, double band, String model, int inputTokens, int outputTokens) {}

    /** The model gave no usable answer. The message names the reason (schema messages, an HTTP status); never the answer or the prompt. */
    class Failure extends RuntimeException {

        public enum Kind {
            /** Timeout, 429, 5xx: trying later may work. */
            TRANSIENT,
            /** The answer broke the result schema after every allowed retry. */
            SCHEMA,
            /** Refused, no key, a schema that does not compile: trying again will not help. */
            PERMANENT
        }

        private static final long serialVersionUID = 1L;

        private final Kind kind;

        public Failure(Kind kind, String message, Throwable cause) {
            super(message, cause);
            this.kind = kind;
        }

        public Kind kind() {
            return kind;
        }
    }
}
