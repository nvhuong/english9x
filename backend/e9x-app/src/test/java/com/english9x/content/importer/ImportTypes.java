package com.english9x.content.importer;

import com.english9x.catalog.api.FieldError;
import com.english9x.catalog.api.QuestionTypeDef;
import com.english9x.catalog.api.SchemaValidator;
import com.english9x.domain.scoring.InteractionKind;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.networknt.schema.JsonSchema;
import com.networknt.schema.JsonSchemaFactory;
import com.networknt.schema.SpecVersion;
import com.networknt.schema.ValidationMessage;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.TreeMap;
import java.util.stream.Stream;

/** The 31 question types straight from their schema files (no database), and a schema-only {@link SchemaValidator} with the same paths and wording as the catalog's. */
final class ImportTypes {

    static final ObjectMapper JSON = new ObjectMapper();
    private static final JsonSchemaFactory FACTORY = JsonSchemaFactory.getInstance(SpecVersion.VersionFlag.V202012);

    private ImportTypes() {}

    static Map<String, QuestionTypeDef> all() {
        Map<String, QuestionTypeDef> out = new TreeMap<>();
        try (Stream<Path> files = Files.list(Path.of("src/main/resources/catalog/question-types"))) {
            for (Path f : files.filter(p -> p.toString().endsWith(".schema.json")).sorted().toList()) {
                JsonNode root = JSON.readTree(f.toFile());
                String code = root.get("code").asText();
                out.put(code, new QuestionTypeDef(code, code.substring(0, code.indexOf('_')), "S", InteractionKind.SHORT_ANSWER, FACTORY.getSchema(root.get("content")),
                        FACTORY.getSchema(root.get("answerKey")), Optional.of(code)));
            }
        } catch (IOException e) {
            throw new IllegalStateException(e);
        }
        return out;
    }

    static QuestionTypeDef type(String code) {
        return all().get(code);
    }

    static SchemaValidator validator(Map<String, QuestionTypeDef> defs) {
        return (typeCode, content, answerKey) -> {
            QuestionTypeDef def = defs.get(typeCode);
            List<FieldError> out = new ArrayList<>();
            check(def.contentSchema(), content, "content", false, out);
            check(def.answerKeySchema(), answerKey, "answerKey", true, out);
            out.sort(Comparator.comparing(FieldError::path).thenComparing(FieldError::message));
            return out;
        };
    }

    private static void check(JsonSchema schema, JsonNode node, String root, boolean confidential, List<FieldError> out) {
        for (ValidationMessage m : schema.validate(node)) {
            String type = m.getType() == null ? "" : m.getType();
            String at = m.getInstanceLocation().toString();
            String path = root + (at.length() > 1 ? at.substring(1) : "");
            if (type.equals("required") && m.getProperty() != null) {
                path += "." + m.getProperty();
            }
            out.add(new FieldError(path, type.equals("required") ? "is required" : confidential ? "does not match the schema" : m.getMessage()));
        }
    }
}
