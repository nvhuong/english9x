package com.english9x.org.members;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;

import com.english9x.org.OrgIT;
import java.time.Instant;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.test.context.TestPropertySource;

/** Guessing join codes is limited per signed-in account and per client address (8 characters are guessable by a patient script). */
@TestPropertySource(properties = {"e9x.org.join.ip-rate-limit=6/15m", "e9x.org.join.user-rate-limit=3/15m"})
class JoinCodeRateLimitIT extends OrgIT {

    private Res join(String code, UUID user) {
        return post("/api/join/" + code, null, user, null);
    }

    @Test
    void guessingIsLimitedPerAccountAndPerAddress() {
        UUID org = org("CENTER");
        UUID admin = orgAdmin(org);
        Res created = post("/api/orgs/" + org + "/join-codes", Map.of("expiresAt", Instant.now().plusSeconds(3600).toString(), "maxUses", 10), admin, org);
        String real = created.body().path("code").asText();

        UUID guesser = user("guesser");
        for (int i = 0; i < 3; i++) {
            assertEquals(410, join("AAAAAAA" + "ABCDEFGH".charAt(i), guesser).status());
        }
        Res limited = join("AAAAAAAZ", guesser);
        assertEquals(429, limited.status(), limited.raw());
        assertEquals("urn:e9x:problem:too-many-attempts", limited.problemType());
        assertNotNull(limited.headers().getFirst("Retry-After"));
        assertEquals(429, join(real, guesser).status(), "even the right code, from an account that used up its attempts");

        // the same address, another account: the address allowance (6, 3 used and one refused did not count) runs out too
        UUID second = user("second");
        for (int i = 0; i < 3; i++) {
            assertEquals(410, join("BBBBBBB" + "ABCDEFGH".charAt(i), second).status());
        }
        UUID honest = user("honest");
        assertEquals(429, join(real, honest).status(), "the address is used up");
    }
}
