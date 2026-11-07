package com.english9x.catalog.internal;

import com.english9x.catalog.api.FieldError;
import com.english9x.catalog.api.QuestionTypeDef;
import com.english9x.catalog.api.QuestionTypes;
import com.english9x.catalog.api.SchemaValidator;
import com.fasterxml.jackson.databind.JsonNode;
import com.networknt.schema.JsonSchema;
import com.networknt.schema.ValidationMessage;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Set;
import org.springframework.stereotype.Component;

/**
 * Validation of a question against the schemas of its type: the JSON Schema first, then the checks a schema cannot express
 * ({@link Consistency}). It logs nothing. The answer key is confidential: a message about it names the rule that failed
 * ("is not an allowed value"), never the value, and a path never carries a property name the author typed there.
 */
@Component
final class SchemaValidatorImpl implements SchemaValidator {

    private final QuestionTypes types;

    SchemaValidatorImpl(QuestionTypes types) {
        this.types = types;
    }

    @Override
    public List<FieldError> validate(String typeCode, JsonNode content, JsonNode answerKey) {
        QuestionTypeDef def = types.get(typeCode);
        List<FieldError> errors = new ArrayList<>();
        boolean contentOk = check(def.contentSchema(), content, "content", false, errors);
        boolean keyOk = check(def.answerKeySchema(), answerKey, "answerKey", true, errors);
        if (contentOk && keyOk) {
            errors.addAll(Consistency.check(def.kind(), content, answerKey));
        }
        errors.sort(Comparator.comparing(FieldError::path).thenComparing(FieldError::message));
        return List.copyOf(errors);
    }

    private static boolean check(JsonSchema schema, JsonNode node, String root, boolean confidential, List<FieldError> out) {
        if (node == null || node.isNull() || node.isMissingNode()) {
            out.add(new FieldError(root, "is required"));
            return false;
        }
        Set<ValidationMessage> messages = schema.validate(node);
        for (ValidationMessage m : messages) {
            out.add(toError(root, confidential, m));
        }
        return messages.isEmpty();
    }

    private static FieldError toError(String root, boolean confidential, ValidationMessage m) {
        String type = m.getType() == null ? "" : m.getType();
        String at = m.getInstanceLocation().toString();            // $, $.options[1], ...
        String path = root + (at.length() > 1 ? at.substring(1) : "");
        // a missing property is named by the schema, so it may be said even for the answer key; an unexpected one was typed by the author
        boolean namesAProperty = type.equals("required")
                || (!confidential && (type.equals("additionalProperties") || type.equals("unevaluatedProperties")));
        if (namesAProperty && m.getProperty() != null && !m.getProperty().isBlank()) {
            path = path + "." + m.getProperty();
        }
        if (confidential) {
            return new FieldError(path, generic(type));
        }
        String message = m.getMessage();
        String prefix = at + ": ";
        if (message.startsWith(prefix)) {
            message = message.substring(prefix.length());
        }
        return new FieldError(path, message);
    }

    private static String generic(String type) {
        return switch (type) {
            case "required" -> "is required";
            case "type" -> "has the wrong type";
            case "enum", "const" -> "is not an allowed value";
            case "pattern", "format" -> "has the wrong format";
            case "minItems", "minLength", "minimum", "exclusiveMinimum", "minProperties", "minContains" -> "is too small or too short";
            case "maxItems", "maxLength", "maximum", "exclusiveMaximum", "maxProperties", "maxContains" -> "is too large or too long";
            case "uniqueItems" -> "has repeated entries";
            case "additionalProperties", "unevaluatedProperties" -> "has a property the schema does not allow";
            default -> "does not match the schema";
        };
    }
}
