package com.english9x.platform.e2e;

import com.english9x.platform.security.ProblemSecurityHandlers;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Profile;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;
import org.springframework.web.servlet.HandlerInterceptor;
import org.springframework.web.servlet.HandlerMapping;
import org.springframework.web.servlet.config.annotation.InterceptorRegistry;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;

/**
 * Every request routed to a seed controller must carry {@code X-E2E-Seed-Secret}; compared in constant time.
 *
 * <p>The decision is made on the ROUTE Spring MVC resolved (the handler's mapping pattern), not on the request's URI:
 * Spring Security and MVC match the decoded path, so a check of the raw URI lets {@code /api/test/%73eed/ping} through.
 * Asking "which route is this about to run?" leaves no spelling to get past it.
 */
@Component
@Profile("e2e")
class SeedSecretInterceptor implements HandlerInterceptor, WebMvcConfigurer {

    static final String HEADER = "X-E2E-Seed-Secret";
    private static final String SEED_ROUTES = "/api/test/";

    private final byte[] secret;
    private final ProblemSecurityHandlers problems;

    SeedSecretInterceptor(@Value("${e9x.e2e.seed-secret:}") String secret, ProblemSecurityHandlers problems) {
        this.secret = secret.getBytes(StandardCharsets.UTF_8);
        this.problems = problems;
    }

    @Override
    public void addInterceptors(InterceptorRegistry registry) {
        registry.addInterceptor(this).addPathPatterns("/**"); // all paths: the route is judged in preHandle, not here
    }

    @Override
    public boolean preHandle(HttpServletRequest request, HttpServletResponse response, Object handler) throws IOException {
        Object route = request.getAttribute(HandlerMapping.BEST_MATCHING_PATTERN_ATTRIBUTE);
        if (!(route instanceof String pattern) || !pattern.startsWith(SEED_ROUTES)) {
            return true;
        }
        String given = request.getHeader(HEADER);
        if (given != null && MessageDigest.isEqual(secret, given.getBytes(StandardCharsets.UTF_8))) {
            return true;
        }
        problems.write(request, response, HttpStatus.FORBIDDEN, "forbidden", "Forbidden", "You do not have access to this resource.");
        return false;
    }
}
