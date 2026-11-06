package com.english9x.identity;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.util.Map;
import org.junit.jupiter.api.Test;
import org.springframework.test.context.TestPropertySource;

/** Behind a configured trusted proxy (here the loopback test client plays Caddy) X-Forwarded-For is used, from the right. */
@TestPropertySource(properties = {"e9x.auth.login-rate-limit=3/15m", "e9x.auth.trusted-proxies=127.0.0.1/32,::1/128"})
class LoginRateLimitTrustedProxyIT extends IdentityIT {

    private Res loginVia(String email, String password, String forwardedFor) {
        return post("/api/auth/login", Map.of("email", email, "password", password), Map.of("X-Forwarded-For", forwardedFor));
    }

    @Test
    void theClientAddressBehindTheProxyIsWhatIsCounted() {
        String email = registerVerified(uniqueEmail(), PASSWORD);
        for (int i = 0; i < 3; i++) {
            assertEquals(401, loginVia(email, "wrong-" + i, "198.51.100.5").status());
        }
        assertEquals(429, loginVia(email, PASSWORD, "198.51.100.5").status(), "that client is blocked");
        assertEquals(200, loginVia(email, PASSWORD, "198.51.100.6").status(), "another client behind the same proxy is not");
    }

    @Test
    void whatTheClientPutAtTheLeftOfTheHeaderNeverMatters() {
        String email = registerVerified(uniqueEmail(), PASSWORD);
        // the proxy appended the real peer 198.51.100.9; the client forged different leading entries each time
        for (int i = 0; i < 3; i++) {
            assertEquals(401, loginVia(email, "wrong-" + i, "10.1." + i + ".1, 198.51.100.9").status());
        }
        assertEquals(429, loginVia(email, PASSWORD, "172.16.0.1, 198.51.100.9").status());
    }
}
