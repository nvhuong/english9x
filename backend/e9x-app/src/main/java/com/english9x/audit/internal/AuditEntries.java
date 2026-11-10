package com.english9x.audit.internal;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.nio.charset.StandardCharsets;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;
import java.util.regex.Pattern;

/** What may be written to the audit log: the shape of names and the content of {@code detail}. Pure, so {@code AuditEntriesTest} covers it without a database. */
public final class AuditEntries {

    private static final Pattern NAME = Pattern.compile("[a-z][a-z0-9]*([.-][a-z0-9]+)*");
    static final int MAX_ACTION = 80;
    static final int MAX_TARGET_TYPE = 60;
    static final int MAX_TARGET_ID = 200;
    /** A detail value is a fact (an id, a role, a count, a name), not a text somebody wrote. */
    static final int MAX_VALUE_LENGTH = 500;
    private static final int MAX_DEPTH = 4;

    /** Key fragments (letters and digits only, lower case) that mean a secret. */
    private static final java.util.List<String> SECRET_FRAGMENTS =
            java.util.List.of("token", "password", "passwd", "secret", "hash", "authorization", "cookie", "apikey", "credential");
    /** Keys that would carry what a person wrote or answered. Record a length or a count instead. */
    private static final java.util.Set<String> FREE_TEXT_KEYS = java.util.Set.of("comment", "text", "body", "answer", "content", "message", "note");

    private static final ObjectMapper JSON = new ObjectMapper();

    private AuditEntries() {}

    public static void checkNames(String action, String targetType, String targetId) {
        if (action == null || action.length() > MAX_ACTION || !NAME.matcher(action).matches()) {
            throw new IllegalArgumentException("audit action must be lower-case words joined by '.' or '-', at most " + MAX_ACTION + " characters");
        }
        if (targetType == null || targetType.length() > MAX_TARGET_TYPE || !NAME.matcher(targetType).matches()) {
            throw new IllegalArgumentException("audit target type must be lower-case words joined by '.' or '-', at most " + MAX_TARGET_TYPE + " characters");
        }
        if (targetId == null || targetId.isEmpty() || targetId.length() > MAX_TARGET_ID) {
            throw new IllegalArgumentException("audit target id must be 1 to " + MAX_TARGET_ID + " characters");
        }
    }

    /** Validates {@code detail} and returns it as JSON text of at most {@code maxBytes} bytes. Never echoes a refused value in the message. */
    public static String detailJson(Map<String, Object> detail, int maxBytes) {
        Map<String, Object> clean = detail == null ? Map.of() : asMap(detail, 0, "detail");
        try {
            String json = JSON.writeValueAsString(clean);
            if (json.getBytes(StandardCharsets.UTF_8).length > maxBytes) {
                throw new IllegalArgumentException("audit detail is larger than " + maxBytes + " bytes (e9x.audit.max-detail-bytes)");
            }
            return json;
        } catch (JsonProcessingException e) {
            throw new IllegalArgumentException("audit detail cannot be written as JSON");
        }
    }

    private static Map<String, Object> asMap(Map<?, ?> in, int depth, String path) {
        if (depth >= MAX_DEPTH) {
            throw new IllegalArgumentException("audit detail is nested too deep at " + path);
        }
        Map<String, Object> out = new LinkedHashMap<>();
        for (Map.Entry<?, ?> e : in.entrySet()) {
            if (!(e.getKey() instanceof String key) || key.isEmpty() || key.length() > 60) {
                throw new IllegalArgumentException("audit detail keys are non-empty strings of at most 60 characters (at " + path + ")");
            }
            checkKey(key, path);
            out.put(key, value(e.getValue(), depth + 1, path + "." + key));
        }
        return out;
    }

    private static void checkKey(String key, String path) {
        String norm = key.toLowerCase(java.util.Locale.ROOT).replaceAll("[^a-z0-9]", "");
        for (String fragment : SECRET_FRAGMENTS) {
            if (norm.contains(fragment)) {
                throw new IllegalArgumentException("audit detail must not carry a secret: key '" + key + "' at " + path + " is refused");
            }
        }
        if (FREE_TEXT_KEYS.contains(norm)) {
            throw new IllegalArgumentException("audit detail must not carry free text: key '" + key + "' at " + path + " is refused (record a length or a count)");
        }
    }

    private static Object value(Object v, int depth, String path) {
        if (v == null || v instanceof Number || v instanceof Boolean) {
            return v;
        }
        if (v instanceof String s) {
            if (s.length() > MAX_VALUE_LENGTH) {
                throw new IllegalArgumentException("audit detail value at " + path + " is longer than " + MAX_VALUE_LENGTH + " characters: it is a text, not a fact");
            }
            return s;
        }
        if (v instanceof UUID || v instanceof Enum<?>) {
            return v.toString();
        }
        if (v instanceof Map<?, ?> m) {
            return asMap(m, depth, path);
        }
        if (v instanceof Collection<?> c) {
            if (depth >= MAX_DEPTH) {
                throw new IllegalArgumentException("audit detail is nested too deep at " + path);
            }
            java.util.List<Object> out = new java.util.ArrayList<>();
            int i = 0;
            for (Object o : c) {
                out.add(value(o, depth + 1, path + "[" + i++ + "]"));
            }
            return out;
        }
        throw new IllegalArgumentException("audit detail value at " + path + " has an unsupported type " + v.getClass().getSimpleName());
    }
}
