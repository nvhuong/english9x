package com.english9x.content.skill.internal;

import com.english9x.content.media.api.MediaAssets;
import com.english9x.content.media.api.MediaKind;
import com.english9x.content.skill.api.PromptTemplate;
import com.english9x.content.skill.internal.SkillProblems.FieldProblem;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.networknt.schema.JsonSchema;
import com.networknt.schema.JsonSchemaFactory;
import com.networknt.schema.SchemaLocation;
import com.networknt.schema.SpecVersion;
import com.networknt.schema.ValidationMessage;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.regex.Pattern;
import java.util.stream.Collectors;
import org.springframework.stereotype.Component;

/**
 * Everything the service checks about the content of a grading skill: the rubric, the criteria (a code, a name, a scale), the prompt template (the four variables
 * of {@link PromptTemplate}, all used), the calibration samples (a band and exactly one of text and a file of this exam) and the JSON Schema of the AI's answer,
 * which must declare a property for every criterion code. Each error has a JSON path (such as {@code criteria[1].code}).
 *
 * <p>The result schema is author text that the grader will compile and run, so it is read as data: it must be a valid 2020-12 schema, may refer only to itself
 * ({@code $ref: "#/..."}), and has no {@code $id} and no other {@code $schema}. Nothing is fetched.
 */
@Component
class SkillValidator {

    private static final int MAX_TEXT = 50_000;
    private static final Pattern CODE = Pattern.compile("^[A-Za-z][A-Za-z0-9_]{0,63}$");
    private static final String DRAFT = "https://json-schema.org/draft/2020-12/schema";
    private static final JsonSchemaFactory FACTORY = JsonSchemaFactory.getInstance(SpecVersion.VersionFlag.V202012);
    private static final JsonSchema META = FACTORY.getSchema(SchemaLocation.of(DRAFT));

    /** The checked, normalised content (only the known fields), or the errors. */
    record Checked(ArrayNode criteria, ArrayNode samples, List<FieldProblem> errors) {}

    private final MediaAssets media;
    private final ObjectMapper json;

    SkillValidator(MediaAssets media, ObjectMapper json) {
        this.media = media;
        this.json = json;
    }

    Checked validate(String examCode, String rubric, JsonNode criteria, String promptTemplate, JsonNode samples, JsonNode resultSchema) {
        List<FieldProblem> errors = new ArrayList<>();
        if (rubric == null || rubric.isBlank()) {
            errors.add(new FieldProblem("rubric", "is required"));
        } else if (rubric.length() > MAX_TEXT) {
            errors.add(new FieldProblem("rubric", "is too long (" + MAX_TEXT + " characters at most)"));
        }
        List<String> codes = new ArrayList<>();
        ArrayNode normCriteria = criteria(criteria, codes, errors);
        template(promptTemplate, errors);
        ArrayNode normSamples = samples(examCode, samples, errors);
        schema(resultSchema, codes, errors);
        return new Checked(normCriteria, normSamples, errors);
    }

    // ---- criteria -----------------------------------------------------------------------------------------------------------------

    private ArrayNode criteria(JsonNode criteria, List<String> codes, List<FieldProblem> errors) {
        ArrayNode out = json.createArrayNode();
        if (criteria == null || !criteria.isArray() || criteria.isEmpty()) {
            errors.add(new FieldProblem("criteria", "needs at least one criterion {code, name, min, max, step}"));
            return out;
        }
        Set<String> seen = new HashSet<>();
        for (int i = 0; i < criteria.size(); i++) {
            JsonNode c = criteria.get(i);
            String at = "criteria[" + i + "]";
            if (!c.isObject()) {
                errors.add(new FieldProblem(at, "must be {code, name, min, max, step}"));
                continue;
            }
            String code = c.path("code").isTextual() ? c.path("code").asText() : null;
            if (code == null || !CODE.matcher(code).matches()) {
                errors.add(new FieldProblem(at + ".code", "must be letters, digits and _ , starting with a letter (64 at most)"));
            } else if (!seen.add(code)) {
                errors.add(new FieldProblem(at + ".code", "repeats " + code));
            } else {
                codes.add(code);
            }
            String name = c.path("name").isTextual() ? c.path("name").asText().trim() : "";
            if (name.isEmpty() || name.length() > 200) {
                errors.add(new FieldProblem(at + ".name", "is required (200 characters at most)"));
            }
            JsonNode min = c.path("min");
            JsonNode max = c.path("max");
            JsonNode step = c.path("step");
            boolean scale = true;
            if (!isFinite(min)) {
                errors.add(new FieldProblem(at + ".min", "must be a number"));
                scale = false;
            }
            if (!isFinite(max)) {
                errors.add(new FieldProblem(at + ".max", "must be a number"));
                scale = false;
            }
            if (scale && max.doubleValue() <= min.doubleValue()) {
                errors.add(new FieldProblem(at + ".max", "must be greater than min"));
                scale = false;
            }
            if (!isFinite(step) || step.doubleValue() <= 0) {
                errors.add(new FieldProblem(at + ".step", "must be a number above 0"));
            } else if (scale) {
                double steps = (max.doubleValue() - min.doubleValue()) / step.doubleValue();
                if (steps < 1 - 1e-9 || Math.abs(steps - Math.rint(steps)) > 1e-9) {
                    errors.add(new FieldProblem(at + ".step", "must divide the range min..max a whole number of times"));
                }
            }
            ObjectNode n = out.addObject();
            n.put("code", code == null ? "" : code);
            n.put("name", name);
            n.set("min", min);
            n.set("max", max);
            n.set("step", step);
        }
        return out;
    }

    private static boolean isFinite(JsonNode n) {
        return n.isNumber() && Double.isFinite(n.doubleValue());
    }

    // ---- the prompt template ------------------------------------------------------------------------------------------------------

    private void template(String template, List<FieldProblem> errors) {
        if (template == null || template.isBlank()) {
            errors.add(new FieldProblem("promptTemplate", "is required"));
            return;
        }
        if (template.length() > MAX_TEXT) {
            errors.add(new FieldProblem("promptTemplate", "is too long (" + MAX_TEXT + " characters at most)"));
            return;
        }
        Set<String> used;
        try {
            used = PromptTemplate.variables(template);
        } catch (IllegalArgumentException e) {
            errors.add(new FieldProblem("promptTemplate", e.getMessage()));
            return;
        }
        List<String> missing = PromptTemplate.VARIABLES.stream().filter(v -> !used.contains(v)).map(v -> "{{" + v + "}}").toList();
        if (!missing.isEmpty()) {
            errors.add(new FieldProblem("promptTemplate", "must use every variable; missing " + String.join(", ", missing)));
        }
    }

    // ---- samples ------------------------------------------------------------------------------------------------------------------

    private ArrayNode samples(String examCode, JsonNode samples, List<FieldProblem> errors) {
        ArrayNode out = json.createArrayNode();
        if (samples == null || samples.isNull()) {
            return out;
        }
        if (!samples.isArray()) {
            errors.add(new FieldProblem("samples", "must be a list of {band, text | mediaId, commentary}"));
            return out;
        }
        for (int i = 0; i < samples.size(); i++) {
            JsonNode s = samples.get(i);
            String at = "samples[" + i + "]";
            if (!s.isObject()) {
                errors.add(new FieldProblem(at, "must be {band, text | mediaId, commentary}"));
                continue;
            }
            if (!isFinite(s.path("band"))) {
                errors.add(new FieldProblem(at + ".band", "must be a number"));
            }
            boolean hasText = s.hasNonNull("text");
            boolean hasMedia = s.hasNonNull("mediaId");
            if (hasText == hasMedia) {
                errors.add(new FieldProblem(at, "needs exactly one of text and mediaId"));
            } else if (hasText && (!s.path("text").isTextual() || s.path("text").asText().isBlank() || s.path("text").asText().length() > MAX_TEXT)) {
                errors.add(new FieldProblem(at + ".text", "must be text (" + MAX_TEXT + " characters at most)"));
            } else if (hasMedia && !mediaOk(examCode, s.path("mediaId"))) {
                errors.add(new FieldProblem(at + ".mediaId", "is not an audio or image file of this exam that you may use"));
            }
            if (s.hasNonNull("commentary") && (!s.path("commentary").isTextual() || s.path("commentary").asText().length() > 5000)) {
                errors.add(new FieldProblem(at + ".commentary", "must be text (5000 characters at most)"));
            }
            ObjectNode n = out.addObject();
            n.set("band", s.path("band"));
            if (hasText) {
                n.set("text", s.path("text"));
            }
            if (hasMedia) {
                n.set("mediaId", s.path("mediaId"));
            }
            if (s.hasNonNull("commentary")) {
                n.set("commentary", s.path("commentary"));
            }
        }
        return out;
    }

    private boolean mediaOk(String examCode, JsonNode id) {
        if (!id.isTextual()) {
            return false;
        }
        try {
            return media.find(UUID.fromString(id.asText())).filter(m -> m.examCode().equals(examCode))
                    .filter(m -> m.kind() == MediaKind.AUDIO || m.kind() == MediaKind.IMAGE).isPresent();
        } catch (IllegalArgumentException e) {
            return false;
        }
    }

    // ---- the result schema --------------------------------------------------------------------------------------------------------

    private void schema(JsonNode schema, List<String> codes, List<FieldProblem> errors) {
        if (schema == null || !schema.isObject()) {
            errors.add(new FieldProblem("resultSchema", "must be a JSON Schema object"));
            return;
        }
        Set<String> reaching = new LinkedHashSet<>();
        reaches(schema, reaching);
        if (!reaching.isEmpty()) {
            errors.add(new FieldProblem("resultSchema", "may refer only to itself ($ref: \"#/...\"), no $id, and $schema only " + DRAFT + " (found " + String.join(", ", reaching) + ")"));
            return;
        }
        Set<ValidationMessage> problems = META.validate(schema);
        if (!problems.isEmpty()) {
            errors.add(new FieldProblem("resultSchema", "is not a valid JSON Schema (2020-12): "
                    + problems.stream().map(m -> m.getInstanceLocation() + " " + m.getType()).sorted().collect(Collectors.joining(", "))));
            return;
        }
        try {
            FACTORY.getSchema(schema).initializeValidators();
        } catch (RuntimeException e) {
            errors.add(new FieldProblem("resultSchema", "does not compile as a JSON Schema: " + e.getMessage()));
            return;
        }
        if (schema.has("type") && !"object".equals(schema.path("type").asText())) {
            errors.add(new FieldProblem("resultSchema.type", "must be \"object\": the AI answers with one JSON object"));
        }
        JsonNode properties = schema.path("properties");
        Set<String> required = new HashSet<>();
        if (schema.path("required").isArray()) {
            schema.path("required").forEach(n -> {
                if (n.isTextual()) {
                    required.add(n.asText());
                }
            });
        }
        List<String> optional = new ArrayList<>();
        for (String code : codes) {
            if (!properties.isObject() || !properties.has(code)) {
                errors.add(new FieldProblem("resultSchema.properties." + code, "must declare a property for the criterion " + code));
            } else if (!numeric(schema, properties.get(code), 0)) {
                errors.add(new FieldProblem("resultSchema.properties." + code, "must be a number: type \"number\" or \"integer\" (or a $ref inside the schema to one)"));
            }
            if (properties.isObject() && properties.has(code) && !required.contains(code)) {
                optional.add(code);
            }
        }
        if (!optional.isEmpty()) {
            errors.add(new FieldProblem("resultSchema.required", "must list every criterion code (the AI may not leave one out); missing " + String.join(", ", optional)));
        }
    }

    /** True when the property is a number or an integer, directly or through {@code $ref}s that stay inside the schema (fail-closed: anything else is false). */
    private static boolean numeric(JsonNode root, JsonNode property, int depth) {
        if (depth > 8 || !property.isObject()) {
            return false;
        }
        JsonNode ref = property.path("$ref");
        if (ref.isTextual()) {
            JsonNode target;
            try {
                target = root.at(ref.asText().substring(1));
            } catch (IllegalArgumentException e) {
                return false;
            }
            return !target.isMissingNode() && numeric(root, target, depth + 1);
        }
        JsonNode type = property.path("type");
        return type.isTextual() && ("number".equals(type.asText()) || "integer".equals(type.asText()));
    }

    /** The addresses the schema would reach outside itself: a {@code $ref} that does not start with #, an {@code $id}, another {@code $schema}. */
    private static void reaches(JsonNode node, Set<String> out) {
        if (node.isObject()) {
            node.fields().forEachRemaining(e -> {
                JsonNode v = e.getValue();
                switch (e.getKey()) {
                    case "$ref", "$dynamicRef", "$recursiveRef" -> {
                        if (v.isTextual() && !v.asText().startsWith("#")) {
                            out.add(e.getKey() + " " + shorten(v.asText()));
                        }
                    }
                    case "$id" -> {
                        if (v.isTextual()) {
                            out.add("$id");
                        }
                    }
                    case "$schema" -> {
                        if (v.isTextual() && !DRAFT.equals(v.asText())) {
                            out.add("$schema " + shorten(v.asText()));
                        }
                    }
                    default -> { }
                }
                reaches(v, out);
            });
        } else if (node.isArray()) {
            node.forEach(c -> reaches(c, out));
        }
    }

    private static String shorten(String s) {
        return s.length() > 60 ? s.substring(0, 60) + "..." : s;
    }
}
