package com.english9x.platform.e2e;

import com.english9x.platform.security.ProblemSecurityHandlers;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Profile;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

/** Every {@code /api/test/seed/**} request must carry {@code X-E2E-Seed-Secret}; compared in constant time. */
@Component
@Profile("e2e")
class SeedSecretFilter extends OncePerRequestFilter {

    static final String HEADER = "X-E2E-Seed-Secret";

    private final byte[] secret;
    private final ProblemSecurityHandlers problems;

    SeedSecretFilter(@Value("${e9x.e2e.seed-secret:}") String secret, ProblemSecurityHandlers problems) {
        this.secret = secret.getBytes(StandardCharsets.UTF_8);
        this.problems = problems;
    }

    @Override
    protected boolean shouldNotFilter(HttpServletRequest request) {
        return !request.getRequestURI().startsWith("/api/test/seed/");
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
            throws ServletException, IOException {
        String given = request.getHeader(HEADER);
        boolean ok = given != null && MessageDigest.isEqual(secret, given.getBytes(StandardCharsets.UTF_8));
        if (!ok) {
            problems.write(request, response, HttpStatus.FORBIDDEN, "forbidden", "Forbidden", "You do not have access to this resource.");
            return;
        }
        chain.doFilter(request, response);
    }
}
