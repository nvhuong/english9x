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
import java.util.regex.Matcher;
import java.util.regex.Pattern;

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
     * @param typedFixtures for templates with more than one path parameter: template -> bindings (parameter name ->
     *     id), each of which must be rejected. Without an entry (or an exemption) a multi-parameter template fails,
     *     because one id repeated in every slot answers 404 whatever the authorisation does.
     * @param call (path template, concrete path) -> HTTP status
     */
    public static Result run(String openApiJson, List<UUID> foreignIds, Map<String, String> exempt,
                             Map<String, List<Map<String, UUID>>> typedFixtures,
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
            List<String> names = new ArrayList<>();
            Matcher m = Pattern.compile("\\{([^}]+)}").matcher(template);
            while (m.find()) {
                names.add(m.group(1));
            }
            List<Map<String, UUID>> bindings = new ArrayList<>();
            if (names.size() == 1) {
                for (UUID id : foreignIds) {
                    bindings.add(Map.of(names.get(0), id));
                }
            } else {
                List<Map<String, UUID>> typed = typedFixtures.getOrDefault(template, List.of());
                if (typed.isEmpty()) {
                    unsweepable.add(template + " (" + names.size() + " path parameters: register a typed fixture or an exemption with a reason)");
                    return;
                }
                for (Map<String, UUID> b : typed) {
                    if (!b.keySet().containsAll(names)) {
                        unsweepable.add(template + " (typed fixture " + b.keySet() + " does not cover " + names + ")");
                        return;
                    }
                    bindings.add(b);
                }
            }
            visited.add(template);
            for (Map<String, UUID> b : bindings) {
                String path = template;
                for (String n : names) {
                    path = path.replace("{" + n + "}", b.get(n).toString());
                }
                int status = call.apply(template, path);
                if (status != 403 && status != 404) {
                    leaks.add("GET " + template + " answered " + status + " for foreign " + b);
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
