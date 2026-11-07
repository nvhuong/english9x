package com.english9x.platform.security;

import com.english9x.org.api.CurrentContextHolder;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationToken;
import org.springframework.web.filter.OncePerRequestFilter;

/**
 * After authentication: binds the authenticated user (JWT {@code sub}) as the {@link CurrentContextHolder} binding of
 * the request thread, so every database transaction in the request runs under that user with Row-Level Security. The
 * binding is released in a {@code finally}, also when the rest of the chain throws.
 *
 * <p>Organisation context: only the personal context ("Cá nhân") exists until P1.5, so the binding never carries an
 * org. A request that DOES send {@code X-Org-Context} is refused with 403 (fail closed) rather than ignored or
 * honoured: silently ignoring it would show a user personal data while they believe they are in an organisation, and
 * honouring it unchecked would be a cross-tenant hole. P1.5 replaces this refusal with the membership check.
 */
public class CurrentContextFilter extends OncePerRequestFilter {

    static final String ORG_HEADER = "X-Org-Context";

    private final ProblemSecurityHandlers problems;

    public CurrentContextFilter(ProblemSecurityHandlers problems) {
        this.problems = problems;
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
            throws ServletException, IOException {
        Authentication auth = SecurityContextHolder.getContext().getAuthentication();
        if (!(auth instanceof JwtAuthenticationToken jwt) || !auth.isAuthenticated()) {
            chain.doFilter(request, response);
            return;
        }
        UUID userId;
        try {
            userId = UUID.fromString(jwt.getToken().getSubject());
        } catch (RuntimeException e) {
            SecurityContextHolder.clearContext();
            problems.write(request, response, HttpStatus.UNAUTHORIZED, "unauthorized", "Unauthorized", "Authentication is required.");
            return;
        }
        if (request.getHeader(ORG_HEADER) != null) {
            problems.write(request, response, HttpStatus.FORBIDDEN, "forbidden", "Forbidden", "You do not have access to this resource.");
            return;
        }
        try (CurrentContextHolder.Scope ignored = CurrentContextHolder.bind(userId, null)) {
            chain.doFilter(request, response);
        }
    }
}
