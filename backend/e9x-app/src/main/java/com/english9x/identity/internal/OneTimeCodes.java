package com.english9x.identity.internal;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import org.springframework.stereotype.Component;

/**
 * Hand-over of a finished Google sign-in from the browser redirect to the SPA. The redirect carries only an opaque
 * code (256 random bits, valid {@code e9x.auth.oauth-code-ttl} = 60 s, redeemable once); the SPA then POSTs it and
 * receives the tokens in a response body, so no access / refresh token is ever put in a URL, history entry, Referer
 * or access log. Held in memory (single instance); a restart in the 60 s window just means signing in again.
 */
@Component
class OneTimeCodes {

    /** Who the code signs in, and the credentials version the sign-in was made against (see auth_refresh_issue). */
    record Redeemed(UUID userId, long credentialsVersion) {}

    private record Entry(Redeemed who, Instant expires) {}

    private static final int MAX = 10_000;

    private final Clock clock;
    private final Duration ttl;
    private final Map<String, Entry> byHash = new LinkedHashMap<>();

    OneTimeCodes(Clock clock, AuthProperties props) {
        this.clock = clock;
        this.ttl = props.oauthCodeTtl();
    }

    synchronized String issue(UUID userId, long credentialsVersion) {
        Instant now = clock.instant();
        byHash.values().removeIf(e -> !e.expires().isAfter(now));
        while (byHash.size() >= MAX) {
            byHash.remove(byHash.keySet().iterator().next());
        }
        String code = TokenCodec.newToken();
        byHash.put(key(code), new Entry(new Redeemed(userId, credentialsVersion), now.plus(ttl)));
        return code;
    }

    synchronized Optional<Redeemed> redeem(String code) {
        if (!TokenCodec.plausible(code)) {
            return Optional.empty();
        }
        Entry e = byHash.remove(key(code));
        if (e == null || !e.expires().isAfter(clock.instant())) {
            return Optional.empty();
        }
        return Optional.of(e.who());
    }

    private static String key(String code) {
        return java.util.HexFormat.of().formatHex(TokenCodec.hash(code));
    }
}
