package com.english9x.catalog.api;

/**
 * One reason a question is refused. {@code path} is where, from the question: {@code content.options[2].key},
 * {@code answerKey.slots[0]}. {@code message} never repeats an answer: for the answer key it names the rule that failed, not the value.
 */
public record FieldError(String path, String message) {}
