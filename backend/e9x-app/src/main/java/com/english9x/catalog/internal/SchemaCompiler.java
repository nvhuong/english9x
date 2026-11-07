package com.english9x.catalog.internal;

import com.fasterxml.jackson.databind.JsonNode;
import com.networknt.schema.JsonSchema;
import com.networknt.schema.JsonSchemaFactory;
import com.networknt.schema.SchemaLocation;
import com.networknt.schema.SpecVersion;
import com.networknt.schema.ValidationMessage;
import java.util.Set;
import java.util.stream.Collectors;

/** Compiles a JSON Schema (2020-12) after checking that it is one. Everything the library needs is bundled: nothing is fetched. */
final class SchemaCompiler {

    private static final JsonSchemaFactory FACTORY = JsonSchemaFactory.getInstance(SpecVersion.VersionFlag.V202012);
    private static final JsonSchema META = FACTORY.getSchema(SchemaLocation.of("https://json-schema.org/draft/2020-12/schema"));

    private SchemaCompiler() {}

    /** @throws IllegalArgumentException when {@code node} is not a valid schema; the message says where, never what was in the data */
    static JsonSchema compile(JsonNode node) {
        Set<ValidationMessage> problems = META.validate(node);
        if (!problems.isEmpty()) {
            throw new IllegalArgumentException("not a valid JSON Schema: "
                    + problems.stream().map(m -> m.getInstanceLocation() + " " + m.getType()).sorted().collect(Collectors.joining(", ")));
        }
        try {
            JsonSchema schema = FACTORY.getSchema(node);
            schema.initializeValidators();
            return schema;
        } catch (RuntimeException e) {
            throw new IllegalArgumentException("JSON Schema does not compile: " + e.getMessage(), e);
        }
    }
}
