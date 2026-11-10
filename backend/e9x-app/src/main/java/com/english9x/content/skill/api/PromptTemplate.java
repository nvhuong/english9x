package com.english9x.content.skill.api;

import com.fasterxml.jackson.databind.JsonNode;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * The prompt template of a grading skill: Mustache variables, nothing else. The four variables are {@code {{rubric}}}, {@code {{samples}}}, {@code {{task}}} and
 * {@code {{response}}}; {@code {{{name}}}} means the same. Sections, inverted sections, partials, comments, set-delimiters and unknown names are refused, so a template
 * is plain text with four holes and renders the same way everywhere.
 *
 * <p>Values are inserted as they are (a prompt is not HTML, so nothing is escaped) and are never read again as a template, so a learner's response that contains
 * {@code {{rubric}}} stays text. A variable without a value renders as the empty text.
 */
public final class PromptTemplate {

    public static final List<String> VARIABLES = List.of("rubric", "samples", "task", "response");

    private PromptTemplate() {}

    /** The variables the template names, in order of first use. @throws IllegalArgumentException when the template is not the supported plain kind; the message says where */
    public static Set<String> variables(String template) {
        Set<String> names = new LinkedHashSet<>();
        scan(template, (literal, name) -> {
            if (name != null) {
                names.add(name);
            }
        });
        return names;
    }

    /** @throws IllegalArgumentException as {@link #variables} */
    public static String render(String template, Map<String, String> values) {
        StringBuilder out = new StringBuilder();
        scan(template, (literal, name) -> {
            if (name == null) {
                out.append(literal);
            } else {
                out.append(values.getOrDefault(name, ""));
            }
        });
        return out.toString();
    }

    /** The text of {@code {{samples}}}: the scored examples a skill carries, in order, for the model to calibrate on. */
    public static String samplesText(JsonNode samples) {
        List<String> parts = new ArrayList<>();
        if (samples != null && samples.isArray()) {
            int i = 1;
            for (JsonNode s : samples) {
                StringBuilder one = new StringBuilder("Sample ").append(i++).append(" (band ").append(s.path("band").asText()).append(")\n");
                if (s.hasNonNull("text")) {
                    one.append(s.path("text").asText());
                } else if (s.hasNonNull("mediaId")) {
                    one.append("[recording ").append(s.path("mediaId").asText()).append(']');
                }
                if (s.hasNonNull("commentary") && !s.path("commentary").asText().isBlank()) {
                    one.append("\nExaminer's commentary: ").append(s.path("commentary").asText());
                }
                parts.add(one.toString());
            }
        }
        return String.join("\n\n", parts);
    }

    private interface Piece {
        /** @param literal the text between tags, when {@code name} is null; @param name the variable of a tag */
        void accept(String literal, String name);
    }

    private static void scan(String template, Piece piece) {
        int at = 0;
        while (at < template.length()) {
            int open = template.indexOf("{{", at);
            if (open < 0) {
                piece.accept(template.substring(at), null);
                return;
            }
            if (open > at) {
                piece.accept(template.substring(at, open), null);
            }
            boolean triple = template.startsWith("{{{", open);
            String close = triple ? "}}}" : "}}";
            int end = template.indexOf(close, open + (triple ? 3 : 2));
            if (end < 0) {
                throw new IllegalArgumentException("{{ at position " + open + " is never closed");
            }
            String name = template.substring(open + (triple ? 3 : 2), end).trim();
            if (name.isEmpty()) {
                throw new IllegalArgumentException("an empty {{}} at position " + open);
            }
            if (!VARIABLES.contains(name)) {
                String first = name.substring(0, 1);
                if ("#^/>!&=".contains(first)) {
                    throw new IllegalArgumentException("{{" + name + "}} at position " + open + " is not supported: a template has the variables " + VARIABLES + " only");
                }
                throw new IllegalArgumentException("{{" + name + "}} at position " + open + " is not a variable of a grading prompt (use " + VARIABLES + ")");
            }
            piece.accept(null, name);
            at = end + close.length();
        }
    }
}
