package com.english9x.org.sweep;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;
import java.util.UUID;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Review Focus #1. Reads an OpenAPI document and calls EVERY operation, whatever its HTTP method, as a member of org A with ids that belong to org B,
 * recording every answer other than 403 / 404 as a leak. A 400 counts too: the body of a fixture is valid, so a validation error before
 * the tenant check means the request got further than a stranger should, or that the fixture proves nothing.
 *
 * <p>An operation is identified as {@code "METHOD /path/{template}"}. There is one exemption mechanism, {@code exempt}: operation -> written reason.
 * <ul>
 *   <li>A read (GET, HEAD, OPTIONS) with no path parameter sends no id and is skipped. With one uuid parameter it walks every foreign id on its own.
 *       Several parameters, or one that is not a uuid (a token, a course code), need a {@link Fixture}.
 *   <li>A write (POST, PUT, PATCH, DELETE) always needs a {@link Fixture} or an exemption, also with no path parameter (the id may be in the
 *       body, or the route may be for admins only). A new write route therefore fails the sweep until its author writes one down.
 *   <li>A write's fixture also needs a positive control: the same route, with the caller's own ids, must answer 2xx. Otherwise a 404 for every foreign
 *       id could just mean a wrong path or a body nobody accepts. Controls run after all foreign calls, because they change data.
 * </ul>
 *
 * <p>The caller supplies how a request is made; the ITs use real signed tokens and the real {@code X-Org-Context} header.
 */
public final class ForeignIdSweep {

    public record Result(Set<String> visited, List<String> unsweepable, List<String> leaks) {}

    /** One request. {@code caller} is null for the sweep's caller (org A's admin), or a name the ITs understand, such as {@link #ADMIN}. */
    public record Call(String method, String template, String path, String body, String caller) {}

    @FunctionalInterface
    public interface Caller {
        int status(Call call);
    }

    public static final String ADMIN = "sys-admin";

    /** One attempt: a value per path parameter (ids or strings such as tokens), and a body when this attempt needs its own. */
    public record Attempt(Map<String, String> slots, String body) {
        public Attempt(Map<String, String> slots) {
            this(slots, null);
        }
    }

    public record Control(Map<String, String> slots, String body, String caller) {
        public static Control of(Map<String, String> slots, String body) {
            return new Control(slots, body, null);
        }
    }

    /**
     * @param body the minimal valid body used unless an attempt has its own; required when the operation declares a required body
     * @param foreign what must be refused. Empty means: walk the foreign ids (one uuid parameter), or one call as it stands (no parameter)
     * @param control must answer 2xx; a write needs one, or {@code noControl}
     * @param noControl why this write has no positive control
     */
    public record Fixture(String body, List<Attempt> foreign, Control control, String noControl) {
        public static Fixture read(List<Attempt> foreign) {
            return new Fixture(null, foreign, null, null);
        }

        public static Fixture write(String body, List<Attempt> foreign, Control control) {
            return new Fixture(body, foreign, control, null);
        }
    }

    private static final ObjectMapper JSON = new ObjectMapper();
    private static final List<String> READS = List.of("get", "head", "options");
    private static final List<String> WRITES = List.of("post", "put", "patch", "delete", "trace");
    private static final Pattern PARAM = Pattern.compile("\\{([^}]+)}");

    private ForeignIdSweep() {}

    /**
     * @param exempt operations that may legitimately answer a foreign id or take none: {@code "METHOD /path"} -> a written reason
     * @param fixtures {@code "METHOD /path"} -> how to call it
     */
    public static Result run(String openApiJson, List<UUID> foreignIds, Map<String, String> exempt, Map<String, Fixture> fixtures, Caller caller)
            throws Exception {
        Set<String> visited = new TreeSet<>();
        List<String> unsweepable = new ArrayList<>();
        List<String> leaks = new ArrayList<>();
        List<Runnable> controls = new ArrayList<>();
        JSON.readTree(openApiJson).path("paths").fields().forEachRemaining(entry -> {
            String template = entry.getKey();
            for (String method : concat(READS, WRITES)) {
                JsonNode op = entry.getValue().path(method);
                if (!op.isMissingNode()) {
                    sweep(method.toUpperCase(Locale.ROOT), WRITES.contains(method), template, entry.getValue(), op, foreignIds, exempt, fixtures, caller,
                            visited, unsweepable, leaks, controls);
                }
            }
        });
        controls.forEach(Runnable::run);
        return new Result(visited, unsweepable, leaks);
    }

    private static void sweep(String method, boolean write, String template, JsonNode pathItem, JsonNode op, List<UUID> foreignIds,
                              Map<String, String> exempt, Map<String, Fixture> fixtures, Caller caller,
                              Set<String> visited, List<String> unsweepable, List<String> leaks, List<Runnable> controls) {
        String id = method + " " + template;
        if (exempt.containsKey(id)) {
            if (exempt.get(id) == null || exempt.get(id).isBlank()) {
                unsweepable.add(id + " (an exemption needs a written reason)");
            }
            return;
        }
        List<String> names = new ArrayList<>();
        Matcher m = PARAM.matcher(template);
        while (m.find()) {
            names.add(m.group(1));
        }
        Fixture fixture = fixtures.get(id);
        if (!write && names.isEmpty() && fixture == null) {
            return; // nothing to put a foreign id in
        }
        if (write && fixture == null) {
            unsweepable.add(id + " (a write endpoint needs a fixture with a valid body and a positive control, or an exemption with a reason)");
            return;
        }
        boolean bodyRequired = op.path("requestBody").path("required").asBoolean(false);
        if (write && bodyRequired && fixture.body() == null
                && (fixture.foreign().isEmpty() || fixture.foreign().stream().anyMatch(a -> a.body() == null))) {
            unsweepable.add(id + " (the operation requires a body: a fixture without one proves nothing, it would be refused as invalid)");
            return;
        }
        if (write && fixture.control() == null && (fixture.noControl() == null || fixture.noControl().isBlank())) {
            unsweepable.add(id + " (a write fixture needs a positive control, or a written reason why it has none)");
            return;
        }
        List<Attempt> attempts = fixture == null || fixture.foreign().isEmpty() ? walk(names, pathItem, op, foreignIds) : fixture.foreign();
        if (attempts == null) {
            unsweepable.add(id + " (" + names + ": several path parameters, or one that is not a uuid, need a typed fixture or an exemption with a reason)");
            return;
        }
        for (Attempt a : attempts) {
            if (!a.slots().keySet().containsAll(names)) {
                unsweepable.add(id + " (typed fixture " + a.slots().keySet() + " does not cover " + names + ")");
                return;
            }
        }
        visited.add(id);
        String defaultBody = fixture == null ? null : fixture.body();
        for (Attempt a : attempts) {
            String path = fill(template, names, a.slots());
            int status = caller.status(new Call(method, template, path, a.body() == null ? defaultBody : a.body(), null));
            if (status != 403 && status != 404) {
                leaks.add(id + " answered " + status + " for foreign " + a.slots() + (a.body() == null ? "" : " with body " + a.body()));
            }
        }
        if (fixture != null && fixture.control() != null) {
            Control c = fixture.control();
            controls.add(() -> {
                int status = caller.status(new Call(method, template, fill(template, names, c.slots()), c.body() == null ? defaultBody : c.body(), c.caller()));
                if (status < 200 || status >= 300) {
                    unsweepable.add(id + " (the positive control answered " + status + ": a refusal of foreign ids proves nothing while the route refuses everybody)");
                }
            });
        }
    }

    /** One uuid parameter: every foreign id. No parameter: one call. Anything else: null. */
    private static List<Attempt> walk(List<String> names, JsonNode pathItem, JsonNode op, List<UUID> foreignIds) {
        if (names.isEmpty()) {
            return List.of(new Attempt(Map.of()));
        }
        if (names.size() != 1) {
            return null;
        }
        List<JsonNode> params = new ArrayList<>();
        pathItem.path("parameters").forEach(params::add);
        op.path("parameters").forEach(params::add);
        for (JsonNode p : params) {
            if ("path".equals(p.path("in").asText()) && !"uuid".equals(p.path("schema").path("format").asText())) {
                return null;
            }
        }
        return foreignIds.stream().map(id -> new Attempt(Map.of(names.get(0), id.toString()))).toList();
    }

    private static String fill(String template, List<String> names, Map<String, String> slots) {
        String path = template;
        for (String n : names) {
            path = path.replace("{" + n + "}", slots.get(n));
        }
        return path;
    }

    private static List<String> concat(List<String> a, List<String> b) {
        List<String> all = new ArrayList<>(a);
        all.addAll(b);
        return all;
    }
}
