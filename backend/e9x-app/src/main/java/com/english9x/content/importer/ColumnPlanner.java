package com.english9x.content.importer;

import com.english9x.catalog.api.QuestionTypeDef;
import com.english9x.content.importer.Column.Kind;
import com.english9x.content.importer.Column.Side;
import com.fasterxml.jackson.databind.JsonNode;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * Derives the columns of a question type from its two JSON Schemas, with no knowledge of any type: a property is a column, a nested object is a
 * column per property ({@code marker.x}), an array of things is a numbered group of columns up to its {@code maxItems} ({@code options.1.key},
 * {@code options.1.text}, {@code options.2.key}, ...), and an array of arrays of values (the accepted variants of each blank) is ONE column per blank whose
 * cell lists the variants separated by {@code |}. A property whose name ends in {@code MediaId} is a file of the zip: {@code audioMediaId} becomes
 * {@code audio_media_file}. The answer key's columns start with {@code answer.}.
 */
final class ColumnPlanner {

    /** An array with no {@code maxItems} gets this many groups, and no array gets more than {@link #CAP}. */
    static final int DEFAULT_REPEAT = 10;
    static final int CAP = 30;

    private ColumnPlanner() {}

    static ColumnPlan plan(QuestionTypeDef def) {
        List<Column> out = new ArrayList<>();
        walk(def.contentSchema().getSchemaNode(), new ArrayList<>(), Side.CONTENT, true, out);
        walk(def.answerKeySchema().getSchemaNode(), new ArrayList<>(), Side.ANSWER, true, out);
        return new ColumnPlan(def.code(), out);
    }

    private static void walk(JsonNode schema, List<Object> path, Side side, boolean required, List<Column> out) {
        String type = schema.path("type").asText("");
        switch (type) {
            case "object" -> {
                Set<String> req = new HashSet<>();
                schema.path("required").forEach(n -> req.add(n.asText()));
                schema.path("properties").fields().forEachRemaining(e -> walk(e.getValue(), append(path, e.getKey()), side, required && req.contains(e.getKey()), out));
            }
            case "array" -> {
                JsonNode items = schema.path("items");
                int min = schema.path("minItems").asInt(0);
                int max = Math.min(CAP, Math.max(1, schema.path("maxItems").asInt(DEFAULT_REPEAT)));
                for (int i = 0; i < max; i++) {
                    List<Object> at = append(path, i);
                    boolean req = required && i < min;
                    if ("array".equals(items.path("type").asText())) {
                        JsonNode inner = items.path("items");
                        Kind k = scalarKind(inner.path("type").asText(""));
                        if (k == null) {
                            throw new IllegalStateException("an array of arrays of " + inner.path("type").asText("?") + " has no column form");
                        }
                        out.add(column(side, at, k, req, true, inner, items));
                    } else {
                        walk(items, at, side, req, out);
                    }
                }
            }
            default -> {
                Kind k = scalarKind(type);
                if (k == null) {
                    throw new IllegalStateException("a schema of type '" + type + "' has no column form");
                }
                Object last = path.get(path.size() - 1);
                boolean media = last instanceof String s && s.endsWith("MediaId");
                out.add(column(side, path, media ? Kind.MEDIA : k, required, false, schema, schema));
            }
        }
    }

    private static Kind scalarKind(String type) {
        return switch (type) {
            case "string" -> Kind.STRING;
            case "integer" -> Kind.INTEGER;
            case "number" -> Kind.NUMBER;
            case "boolean" -> Kind.BOOLEAN;
            default -> null;
        };
    }

    private static Column column(Side side, List<Object> path, Kind kind, boolean required, boolean list, JsonNode valueSchema, JsonNode titled) {
        List<String> allowed = new ArrayList<>();
        valueSchema.path("enum").forEach(n -> allowed.add(n.asText()));
        if (kind == Kind.BOOLEAN) {
            allowed.add("TRUE");
            allowed.add("FALSE");
        }
        return new Column(header(side, path), side, List.copyOf(path), kind, required, list, List.copyOf(allowed), titled.path("title").asText(""),
                constraint(valueSchema, kind, list, titled), valueSchema);
    }

    static String header(Side side, List<Object> path) {
        StringBuilder sb = new StringBuilder(side == Side.ANSWER ? "answer" : "");
        for (int i = 0; i < path.size(); i++) {
            Object seg = path.get(i);
            String text = seg instanceof Integer n ? String.valueOf(n + 1) : (String) seg;
            if (i == path.size() - 1 && seg instanceof String s && s.endsWith("MediaId")) {
                text = s.substring(0, s.length() - "MediaId".length()).toLowerCase(java.util.Locale.ROOT) + "_media_file";
            }
            if (sb.length() > 0) {
                sb.append('.');
            }
            sb.append(text);
        }
        return sb.toString();
    }

    private static String constraint(JsonNode s, Kind kind, boolean list, JsonNode titled) {
        List<String> parts = new ArrayList<>();
        if (kind == Kind.MEDIA) {
            parts.add("tên tệp trong file zip media");
        }
        if (list) {
            parts.add("các biến thể chấp nhận được, cách nhau bởi |");
        }
        if (s.has("minLength") || s.has("maxLength")) {
            parts.add("độ dài " + s.path("minLength").asInt(0) + (s.has("maxLength") ? "-" + s.path("maxLength").asInt() : "+") + " ký tự");
        }
        if (s.has("minimum") || s.has("maximum")) {
            parts.add("từ " + (s.has("minimum") ? s.get("minimum").asText() : "-") + " đến " + (s.has("maximum") ? s.get("maximum").asText() : "-"));
        }
        if (s.has("pattern") && kind == Kind.STRING && !s.path("pattern").asText().equals("\\S")) {
            parts.add("khớp " + s.get("pattern").asText());
        }
        if (s.has("enum")) {
            List<String> v = new ArrayList<>();
            s.get("enum").forEach(n -> v.add(n.asText()));
            parts.add("một trong: " + String.join(", ", v));
        }
        if (kind == Kind.BOOLEAN) {
            parts.add("TRUE hoặc FALSE");
        }
        if (titled != s && titled.has("maxItems")) {
            parts.add("tối đa " + titled.get("maxItems").asInt() + " biến thể");
        }
        return String.join("; ", parts);
    }

    private static List<Object> append(List<Object> path, Object seg) {
        List<Object> next = new ArrayList<>(path);
        next.add(seg);
        return next;
    }
}
