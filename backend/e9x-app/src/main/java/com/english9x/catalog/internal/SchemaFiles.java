package com.english9x.catalog.internal;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.IOException;
import java.io.InputStream;
import java.util.Map;
import java.util.TreeMap;
import org.springframework.core.io.Resource;
import org.springframework.core.io.support.PathMatchingResourcePatternResolver;

/**
 * The schema files of the question types: one {@code <CODE>.schema.json} per type holding {@code {"code", "content", "answerKey"}},
 * the last two being complete JSON Schemas (2020-12) of the question's content and of its answer key.
 */
final class SchemaFiles {

    static final String DEFAULT_LOCATION = "classpath*:catalog/question-types/*.schema.json";
    private static final String SUFFIX = ".schema.json";
    private static final ObjectMapper JSON = new ObjectMapper();

    record SchemaFile(String code, JsonNode content, JsonNode answerKey) {}

    private final String location;

    SchemaFiles(String location) {
        this.location = location;
    }

    /** By code. A file that cannot be read, whose code differs from its name, or that lacks a part, stops the start-up. */
    Map<String, SchemaFile> load() {
        Map<String, SchemaFile> files = new TreeMap<>();
        try {
            for (Resource r : new PathMatchingResourcePatternResolver().getResources(location)) {
                String name = r.getFilename();
                if (name == null || !name.endsWith(SUFFIX)) {
                    continue;
                }
                String code = name.substring(0, name.length() - SUFFIX.length());
                JsonNode root;
                try (InputStream in = r.getInputStream()) {
                    root = JSON.readTree(in);
                }
                if (root == null || !root.isObject() || !code.equals(root.path("code").asText())) {
                    throw new IllegalStateException("question type schema file " + name + " must be an object whose \"code\" is " + code);
                }
                if (!root.path("content").isObject() || !root.path("answerKey").isObject()) {
                    throw new IllegalStateException("question type schema file " + name + " needs a \"content\" and an \"answerKey\" schema");
                }
                if (files.put(code, new SchemaFile(code, root.get("content"), root.get("answerKey"))) != null) {
                    throw new IllegalStateException("two schema files for question type " + code);
                }
            }
        } catch (IOException e) {
            throw new IllegalStateException("cannot read the question type schema files", e);
        }
        return files;
    }
}
