package com.english9x.platform.security;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.english9x.org.api.CurrentContextHolder;
import com.english9x.org.api.OrgMembershipCheck;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.time.Instant;
import java.util.HashSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;
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

    /** Who belongs to what; records the binding that was in force when it was asked. */
    private final Set<String> memberships = new HashSet<>();
    private final AtomicReference<Optional<CurrentContextHolder.Binding>> boundWhenAsked = new AtomicReference<>();
    private final OrgMembershipCheck membership = (user, org) -> {
        boundWhenAsked.set(CurrentContextHolder.current());
        return memberships.contains(user + "/" + org);
    };
    private final CurrentContextFilter filter = new CurrentContextFilter(new ProblemSecurityHandlers(new ObjectMapper()), membership);

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
    void aMembersOrganisationIsBoundForTheRequestAndUnboundAfterwards() throws Exception {
        UUID user = UUID.randomUUID();
        UUID org = UUID.randomUUID();
        memberships.add(user + "/" + org);
        authenticate(user.toString());
        MockHttpServletRequest req = new MockHttpServletRequest("GET", "/api/x");
        req.addHeader("X-Org-Context", org.toString().toUpperCase());
        AtomicReference<Optional<CurrentContextHolder.Binding>> seen = new AtomicReference<>();
        filter.doFilter(req, new MockHttpServletResponse(), (rq, rs) -> seen.set(CurrentContextHolder.current()));
        assertEquals(Optional.of(org), seen.get().orElseThrow().orgId());
        assertEquals(user, seen.get().orElseThrow().userId());
        assertEquals(Optional.empty(), boundWhenAsked.get().orElseThrow().orgId(), "membership is asked in the user's personal context, not the requested one");
        assertTrue(CurrentContextHolder.current().isEmpty(), "unbound after the request");
    }

    @Test
    void theOrganisationIsUnboundWhenTheChainThrows() {
        UUID user = UUID.randomUUID();
        UUID org = UUID.randomUUID();
        memberships.add(user + "/" + org);
        authenticate(user.toString());
        MockHttpServletRequest req = new MockHttpServletRequest("GET", "/api/x");
        req.addHeader("X-Org-Context", org.toString());
        assertThrows(IllegalStateException.class, () -> filter.doFilter(req, new MockHttpServletResponse(), (rq, rs) -> { throw new IllegalStateException("boom"); }));
        assertTrue(CurrentContextHolder.current().isEmpty());
    }

    @Test
    void aNonMemberAndEveryMalformedValueAreRefusedBeforeAnythingRuns() throws Exception {
        UUID member = UUID.randomUUID();
        UUID other = UUID.randomUUID();
        memberships.add(member + "/" + other);
        authenticate(member.toString());
        List<String[]> cases = List.of(
                new String[] {UUID.randomUUID().toString()}, new String[] {""}, new String[] {" "}, new String[] {"x"},
                new String[] {other + " "}, new String[] {other.toString(), other.toString()}, new String[] {"{" + other + "}"},
                new String[] {other.toString().replace("-", "")});
        for (String[] values : cases) {
            MockHttpServletRequest req = new MockHttpServletRequest("GET", "/api/x");
            for (String v : values) {
                req.addHeader("X-Org-Context", v);
            }
            MockHttpServletResponse res = new MockHttpServletResponse();
            AtomicReference<Boolean> reached = new AtomicReference<>(false);
            filter.doFilter(req, res, (rq, rs) -> reached.set(true));
            assertFalse(reached.get(), String.join("|", values));
            assertEquals(403, res.getStatus(), String.join("|", values));
            assertTrue(res.getContentType().startsWith("application/problem+json"));
            assertTrue(CurrentContextHolder.current().isEmpty());
        }
    }

    @Test
    void aNonMemberIsRefused() throws Exception {
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
