package com.english9x.catalog.api;

/** The code is not a seeded question type. */
public class UnknownQuestionTypeException extends RuntimeException {
    private static final long serialVersionUID = 1L;

    private final String code;

    public UnknownQuestionTypeException(String code) {
        super("unknown question type: " + code);
        this.code = code;
    }

    public String code() {
        return code;
    }
}
