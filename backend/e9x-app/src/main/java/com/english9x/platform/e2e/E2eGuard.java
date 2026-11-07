package com.english9x.platform.e2e;

import jakarta.annotation.PostConstruct;
import java.net.URI;
import java.util.List;
import java.util.Set;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Profile;
import org.springframework.core.env.Environment;
import org.springframework.stereotype.Component;

/**
 * The E2E seed API creates users with chosen passwords, so it must never be live anywhere real. This refuses to start
 * the application when the {@code e2e} profile is combined with any sign of a real deployment:
 * <ul>
 *   <li>{@code E9X_DOMAIN} (property {@code e9x.domain}) is anything other than {@code localhost} / 127.0.0.1;
 *   <li>the public base URL ({@code E9X_PUBLIC_BASE_URL}) is not on localhost either;
 *   <li>a production profile ({@code prod}, {@code production}) is active, or {@code E9X_ENV}/{@code e9x.environment} says production;
 *   <li>no seed secret ({@code E9X_E2E_SEED_SECRET}, at least 16 characters) is configured. Every seed request must carry it.
 * </ul>
 */
@Component
@Profile("e2e")
class E2eGuard {

    private static final Set<String> LOCAL = Set.of("localhost", "127.0.0.1", "[::1]");

    private final Environment env;
    private final String domain;
    private final String publicBaseUrl;
    private final String secret;

    E2eGuard(Environment env, @Value("${e9x.domain:}") String domain, @Value("${e9x.public-base-url:}") String publicBaseUrl,
             @Value("${e9x.e2e.seed-secret:}") String secret) {
        this.env = env;
        this.domain = domain;
        this.publicBaseUrl = publicBaseUrl;
        this.secret = secret;
    }

    @PostConstruct
    void check() {
        List<String> problems = problems(env, domain, publicBaseUrl, secret);
        if (!problems.isEmpty()) {
            throw new IllegalStateException("refusing to start with the e2e profile (it exposes a test-data API): "
                    + String.join("; ", problems));
        }
    }

    static List<String> problems(Environment env, String domain, String publicBaseUrl, String secret) {
        java.util.ArrayList<String> out = new java.util.ArrayList<>();
        if (!LOCAL.contains(domain.strip().toLowerCase())) {
            out.add("E9X_DOMAIN must be localhost (is '" + domain + "')");
        }
        String host = "";
        try {
            host = URI.create(publicBaseUrl.strip()).getHost();
        } catch (RuntimeException ignored) {
            // falls through to the failure below
        }
        if (host == null || !LOCAL.contains(host.toLowerCase())) {
            out.add("E9X_PUBLIC_BASE_URL must point at localhost");
        }
        for (String p : env.getActiveProfiles()) {
            if (p.equalsIgnoreCase("prod") || p.equalsIgnoreCase("production")) {
                out.add("production profile '" + p + "' is active");
            }
        }
        if ("production".equalsIgnoreCase(env.getProperty("e9x.environment", ""))
                || "production".equalsIgnoreCase(System.getenv().getOrDefault("E9X_ENV", ""))) {
            out.add("the environment is marked production");
        }
        if (secret.strip().length() < 16) {
            out.add("E9X_E2E_SEED_SECRET (at least 16 characters) is required");
        }
        return out;
    }
}
