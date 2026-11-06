package com.english9x.platform.e2e;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.mock.env.MockEnvironment;

/** The seed API must not start next to anything that looks like a real deployment. */
class E2eGuardTest {

    private static final String SECRET = "0123456789abcdef-secret";

    private static List<String> check(MockEnvironment env, String domain, String baseUrl, String secret) {
        return E2eGuard.problems(env, domain, baseUrl, secret);
    }

    @Test
    void localStackPasses() {
        assertEquals(List.of(), check(new MockEnvironment().withProperty("spring.profiles.active", "e2e"), "localhost", "https://localhost", SECRET));
    }

    @Test
    void aRealDomainIsRefused() {
        assertTrue(check(new MockEnvironment(), "english9x.vn", "https://localhost", SECRET).toString().contains("E9X_DOMAIN"));
        assertTrue(check(new MockEnvironment(), "", "https://localhost", SECRET).toString().contains("E9X_DOMAIN"), "blank is not localhost");
        assertTrue(check(new MockEnvironment(), "localhost.evil.com", "https://localhost", SECRET).toString().contains("E9X_DOMAIN"));
    }

    @Test
    void aRealPublicUrlIsRefused() {
        assertFalse(check(new MockEnvironment(), "localhost", "https://english9x.vn", SECRET).isEmpty());
        assertFalse(check(new MockEnvironment(), "localhost", "", SECRET).isEmpty());
        assertFalse(check(new MockEnvironment(), "localhost", "https://localhost.evil.com", SECRET).isEmpty());
    }

    @Test
    void productionSignalsAreRefused() {
        MockEnvironment prod = new MockEnvironment();
        prod.setActiveProfiles("e2e", "prod");
        assertTrue(check(prod, "localhost", "https://localhost", SECRET).toString().contains("production profile"));
        assertFalse(check(new MockEnvironment().withProperty("e9x.environment", "production"), "localhost", "https://localhost", SECRET).isEmpty());
    }

    @Test
    void aMissingOrShortSecretIsRefused() {
        assertFalse(check(new MockEnvironment(), "localhost", "https://localhost", "").isEmpty());
        assertFalse(check(new MockEnvironment(), "localhost", "https://localhost", "short").isEmpty());
    }
}
