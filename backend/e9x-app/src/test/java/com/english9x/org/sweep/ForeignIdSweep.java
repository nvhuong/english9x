package com.english9x.org.sweep;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;
import java.util.UUID;
import java.util.function.BiFunction;

/**
 * Review Focus #1. Reads an OpenAPI document, finds every GET operation with path parameters, calls it as a member
 * of org A with ids that belong to org B, and records every answer other than 403 / 404 as a leak.
 *
 * <p>The caller supplies how a request is made ({@code call}: path -> HTTP status); P1.5 plugs in real tokens.
 */
public final class ForeignIdSweep {

    public record Result(Set<String> visited, List<String> unsweepable, List<String> leaks) {}

    private static final ObjectMapper JSON = new ObjectMapper();

    private ForeignIdSweep() {}

    /**
     * @param exempt path templates that may legitimately answer a foreign id (each needs a written reason)
     * @param call (path template, concrete path) -> HTTP status
     */
    public static Result run(String openApiJson, List<UUID> foreignIds, Map<String, String> exempt,
                             BiFunction<String, String, Integer> call) throws Exception {
        JsonNode paths = JSON.readTree(openApiJson).path("paths");
        Set<String> visited = new TreeSet<>();
        List<String> unsweepable = new ArrayList<>();
        List<String> leaks = new ArrayList<>();
        paths.fields().forEachRemaining(entry -> {
            String template = entry.getKey();
            JsonNode get = entry.getValue().path("get");
            if (get.isMissingNode() || !template.contains("{") || exempt.containsKey(template)) {
                return;
            }
            boolean uuidParams = true;
            for (JsonNode p : concat(entry.getValue().path("parameters"), get.path("parameters"))) {
                if ("path".equals(p.path("in").asText()) && !"uuid".equals(p.path("schema").path("format").asText())) {
                    uuidParams = false;
                }
            }
            if (!uuidParams) {
                unsweepable.add(template + " (non-uuid path parameter: add a typed fixture or an exemption)");
                return;
            }
            visited.add(template);
            for (UUID id : foreignIds) {
                String path = template.replaceAll("\\{[^}]+}", id.toString());
                int status = call.apply(template, path);
                if (status != 403 && status != 404) {
                    leaks.add("GET " + template + " answered " + status + " for foreign id " + id);
                }
            }
        });
        return new Result(visited, unsweepable, leaks);
    }

    private static List<JsonNode> concat(JsonNode a, JsonNode b) {
        List<JsonNode> all = new ArrayList<>();
        a.forEach(all::add);
        b.forEach(all::add);
        return all;
    }
}
