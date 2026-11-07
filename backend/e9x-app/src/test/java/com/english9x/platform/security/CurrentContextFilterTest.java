package com.english9x.platform.security;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.english9x.org.api.CurrentContextHolder;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.time.Instant;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockFilterChain;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationToken;

/** The request filter that binds the authenticated user for Row-Level Security. */
class CurrentContextFilterTest {

    private final CurrentContextFilter filter = new CurrentContextFilter(new ProblemSecurityHandlers(new ObjectMapper()));

    @AfterEach
    void clean() {
        SecurityContextHolder.clearContext();
        CurrentContextHolder.clear();
    }

    private static void authenticate(String subject) {
        Jwt jwt = Jwt.withTokenValue("t").header("alg", "HS256").subject(subject).issuedAt(Instant.now()).expiresAt(Instant.now().plusSeconds(60)).build();
        SecurityContextHolder.getContext().setAuthentication(new JwtAuthenticationToken(jwt, java.util.List.of()));
    }

    @Test
    void bindsTheUserForTheRestOfTheChainAndUnbindsAfterwards() throws Exception {
        UUID id = UUID.randomUUID();
        authenticate(id.toString());
        AtomicReference<Optional<CurrentContextHolder.Binding>> seen = new AtomicReference<>();
        filter.doFilter(new MockHttpServletRequest(), new MockHttpServletResponse(),
                (rq, rs) -> seen.set(CurrentContextHolder.current()));
        assertEquals(id, seen.get().orElseThrow().userId());
        assertTrue(seen.get().orElseThrow().orgId().isEmpty(), "personal context only until P1.5");
        assertTrue(CurrentContextHolder.current().isEmpty(), "unbound after the request");
    }

    @Test
    void unbindsEvenWhenTheChainThrows() {
        authenticate(UUID.randomUUID().toString());
        assertThrows(IllegalStateException.class, () -> filter.doFilter(new MockHttpServletRequest(), new MockHttpServletResponse(),
                (rq, rs) -> {
                    assertTrue(CurrentContextHolder.current().isPresent());
                    throw new IllegalStateException("boom");
                }));
        assertTrue(CurrentContextHolder.current().isEmpty(), "a failing request must not leave its user on the pooled thread");
    }

    @Test
    void anonymousRequestsBindNothing() throws Exception {
        AtomicReference<Boolean> bound = new AtomicReference<>();
        filter.doFilter(new MockHttpServletRequest(), new MockHttpServletResponse(),
                (rq, rs) -> bound.set(CurrentContextHolder.current().isPresent()));
        assertFalse(bound.get());
    }

    @Test
    void anOrgContextHeaderIsRefusedBeforeAnythingRuns() throws Exception {
        authenticate(UUID.randomUUID().toString());
        MockHttpServletRequest req = new MockHttpServletRequest("GET", "/api/x");
        req.addHeader("X-Org-Context", UUID.randomUUID().toString());
        MockHttpServletResponse res = new MockHttpServletResponse();
        AtomicReference<Boolean> reached = new AtomicReference<>(false);
        filter.doFilter(req, res, new MockFilterChain() {
            @Override
            public void doFilter(jakarta.servlet.ServletRequest a, jakarta.servlet.ServletResponse b) {
                reached.set(true);
            }
        });
        assertFalse(reached.get());
        assertEquals(403, res.getStatus());
        assertTrue(res.getContentType().startsWith("application/problem+json"));
        assertTrue(CurrentContextHolder.current().isEmpty());
    }

    @Test
    void aMalformedSubjectIs401AndBindsNothing() throws Exception {
        authenticate("not-a-uuid");
        MockHttpServletResponse res = new MockHttpServletResponse();
        filter.doFilter(new MockHttpServletRequest("GET", "/api/x"), res, (rq, rs) -> { throw new AssertionError("must not run"); });
        assertEquals(401, res.getStatus());
    }
}
