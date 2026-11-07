package com.english9x.catalog.api;

import com.fasterxml.jackson.databind.JsonNode;
import java.util.List;

/** Checks a question's content and answer key against the schemas of its type. */
public interface SchemaValidator {

    /**
     * Every reason the question is refused, with JSON paths; empty when it is valid. A missing ({@code null} or JSON null) content or
     * answer key is an error, not an exception. Nothing is logged and no answer is repeated in a message.
     *
     * @throws UnknownQuestionTypeException when {@code typeCode} is not a seeded type
     */
    List<FieldError> validate(String typeCode, JsonNode content, JsonNode answerKey);
}
