package com.english9x.platform.jobs;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import org.springframework.boot.convert.DurationStyle;
import org.springframework.core.env.Environment;

/**
 * Retry delays per job type. Retries are the attempts after the first: {@code n} delays mean at most {@code n + 1}
 * attempts, and the delay before retry {@code k} is {@code delays[k-1]}.
 *
 * <p>Lookup order for a type:
 * <ol>
 *   <li>{@code e9x.jobs.<type>.retry-delays} (the general form);
 *   <li>the spec §15.1 key the type is an alias of ({@code ai_grade} to {@code e9x.grading.ai.retry-delays}), so the
 *       value lives in exactly one place;
 *   <li>{@code e9x.jobs.default-retry-delays};
 *   <li>the built-in {@value #BUILT_IN_DEFAULT}.
 * </ol>
 * An empty value means no retries. A malformed value is an error, not a silent default.
 */
class RetryPolicy {

    static final String BUILT_IN_DEFAULT = "30s,2m,10m";
    private static final String DEFAULT_KEY = "e9x.jobs.default-retry-delays";
    private static final Map<String, String> SPEC_KEYS = Map.of("ai_grade", "e9x.grading.ai.retry-delays");

    private final Environment environment;

    RetryPolicy(Environment environment) {
        this.environment = environment;
    }

    List<Duration> delays(String type) {
        String typeKey = "e9x.jobs." + type + ".retry-delays";
        String[] candidates = {typeKey, SPEC_KEYS.get(type), DEFAULT_KEY};
        for (String key : candidates) {
            if (key != null && environment.containsProperty(key)) {
                return parse(key, environment.getProperty(key, ""));
            }
        }
        return parse("built-in default", BUILT_IN_DEFAULT);
    }

    /** How long a handler's lease keeps being renewed: {@code e9x.jobs.<type>.max-run}, then {@code e9x.jobs.max-run}, then 30m. */
    Duration maxRun(String type) {
        for (String key : new String[] {"e9x.jobs." + type + ".max-run", "e9x.jobs.max-run"}) {
            if (environment.containsProperty(key)) {
                try {
                    return DurationStyle.detectAndParse(environment.getProperty(key, "").trim());
                } catch (RuntimeException e) {
                    throw new IllegalArgumentException("invalid duration in " + key, e);
                }
            }
        }
        return Duration.ofMinutes(30);
    }

    /** First attempt plus one per retry delay. */
    int maxAttempts(String type) {
        return 1 + delays(type).size();
    }

    private static List<Duration> parse(String key, String value) {
        List<Duration> out = new ArrayList<>();
        if (value.isBlank()) {
            return out;
        }
        for (String part : value.split(",")) {
            Duration d;
            try {
                d = DurationStyle.detectAndParse(part.trim());
            } catch (RuntimeException e) {
                throw new IllegalArgumentException("invalid retry delay '" + part.trim() + "' in " + key, e);
            }
            if (d.isNegative() || d.isZero()) {
                throw new IllegalArgumentException("retry delay must be positive: '" + part.trim() + "' in " + key);
            }
            out.add(d);
        }
        return List.copyOf(out);
    }
}
