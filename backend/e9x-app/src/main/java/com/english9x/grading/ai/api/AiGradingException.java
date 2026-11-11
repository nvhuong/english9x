package com.english9x.grading.ai.api;

/** The model gave no usable answer. The message never carries the learner's words, the prompt or the key. */
public class AiGradingException extends RuntimeException {

    public enum Kind {
        /** Timeout, 429, 5xx, a broken connection: trying later may work. */
        TRANSIENT,
        /** The answer broke the skill's schema after every allowed retry. */
        SCHEMA,
        /** Refused (4xx), no key, nothing to send: trying again will not help without a change. */
        PERMANENT
    }

    private static final long serialVersionUID = 1L;

    private final Kind kind;

    public AiGradingException(Kind kind, String message) {
        super(message);
        this.kind = kind;
    }

    public AiGradingException(Kind kind, String message, Throwable cause) {
        super(message, cause);
        this.kind = kind;
    }

    public Kind kind() {
        return kind;
    }
}
