package com.english9x.platform.security;

import com.english9x.org.api.CurrentContextHolder;
import com.english9x.org.api.OrgMembershipCheck;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.util.Collections;
import java.util.List;
import java.util.Locale;
import java.util.UUID;
import java.util.regex.Pattern;
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
 * <p>Organisation context ({@code X-Org-Context}, spec §4): absent means the personal context ("Cá nhân"). A value must be one
 * canonical UUID, sent once, naming an organisation the user belongs to; anything else (malformed, repeated, unknown
 * organisation, somebody else's) is refused with the same 403 problem+json rendered here, before MVC, so nothing in the
 * answer tells "no such organisation" from "not a member". Membership is asked of the database on every request (never read
 * from the token), and the transaction hook asks again when the request's transaction begins. The binding lives for this
 * request only and is released in a {@code finally}.
 */
public class CurrentContextFilter extends OncePerRequestFilter {

    static final String ORG_HEADER = "X-Org-Context";

    private static final Pattern CANONICAL_UUID = Pattern.compile("[0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{12}");

    private final ProblemSecurityHandlers problems;
    private final OrgMembershipCheck membership;

    public CurrentContextFilter(ProblemSecurityHandlers problems, OrgMembershipCheck membership) {
        this.problems = problems;
        this.membership = membership;
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
        List<String> headers = Collections.list(request.getHeaders(ORG_HEADER));
        UUID orgId = null;
        if (!headers.isEmpty()) {
            orgId = parse(headers);
            boolean member = false;
            if (orgId != null) {
                try (CurrentContextHolder.Scope ignored = CurrentContextHolder.bind(userId, null)) {
                    member = membership.isMember(userId, orgId);
                }
            }
            if (!member) {
                problems.write(request, response, HttpStatus.FORBIDDEN, "forbidden", "Forbidden", "You do not have access to this resource.");
                return;
            }
        }
        try (CurrentContextHolder.Scope ignored = CurrentContextHolder.bind(userId, orgId)) {
            chain.doFilter(request, response);
        }
    }

    /** One header, one canonical UUID; null for anything else. */
    private static UUID parse(List<String> values) {
        if (values.size() != 1) {
            return null;
        }
        String v = values.get(0);
        if (v == null || !CANONICAL_UUID.matcher(v).matches()) {
            return null;
        }
        return UUID.fromString(v.toLowerCase(Locale.ROOT));
    }
}
