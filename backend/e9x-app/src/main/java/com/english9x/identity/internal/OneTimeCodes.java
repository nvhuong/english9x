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
 * or access log. The code only works together with a second secret held in a cookie of the browser that finished the
 * sign-in (see {@link LoginBrowserCookie}). When the server first has to ask for consent, the code may stay alive while
 * the person reads ({@code e9x.auth.oauth-consent-ttl} from when it was issued). Held in memory (single instance); a
 * restart in that window just means signing in again.
 */
@Component
class OneTimeCodes {

    /** A code and the value for its browser-binding cookie. */
    record Issued(String code, String browserKey) {}

    /** Who the code signs in, and the credentials version the sign-in was made against (see auth_refresh_issue). */
    record Redeemed(UUID userId, long credentialsVersion, Instant issuedAt) {}

    private record Entry(Redeemed who, byte[] browserHash, Instant expires) {}

    private static final int MAX = 10_000;

    private final Clock clock;
    private final Duration ttl;
    private final Duration consentTtl;
    private final Map<String, Entry> byHash = new LinkedHashMap<>();

    OneTimeCodes(Clock clock, AuthProperties props) {
        this.clock = clock;
        this.ttl = props.oauthCodeTtl();
        this.consentTtl = props.oauthConsentTtl();
    }

    synchronized Issued issue(UUID userId, long credentialsVersion) {
        Instant now = clock.instant();
        byHash.values().removeIf(e -> !e.expires().isAfter(now));
        while (byHash.size() >= MAX) {
            byHash.remove(byHash.keySet().iterator().next());
        }
        String code = TokenCodec.newToken();
        String browserKey = TokenCodec.newToken();
        byHash.put(key(code), new Entry(new Redeemed(userId, credentialsVersion, now), TokenCodec.hash(browserKey), now.plus(ttl)));
        return new Issued(code, browserKey);
    }

    /** Spends the code. A wrong browser spends nothing: it must not be able to burn the rightful browser's code. */
    synchronized Optional<Redeemed> redeem(String code, String browserKey) {
        if (!TokenCodec.plausible(code) || !TokenCodec.plausible(browserKey)) {
            return Optional.empty();
        }
        Entry e = byHash.get(key(code));
        if (e == null || !e.expires().isAfter(clock.instant()) || !TokenCodec.same(e.browserHash(), TokenCodec.hash(browserKey))) {
            return Optional.empty();
        }
        byHash.remove(key(code));
        return Optional.of(e.who());
    }

    /** Puts a redeemed code back while the person answers the consent step; never past the consent window of its issue. */
    synchronized void holdForConsent(String code, String browserKey, Redeemed who) {
        Instant deadline = who.issuedAt().plus(consentTtl);
        if (deadline.isAfter(clock.instant())) {
            byHash.put(key(code), new Entry(who, TokenCodec.hash(browserKey), deadline));
        }
    }

    private static String key(String code) {
        return java.util.HexFormat.of().formatHex(TokenCodec.hash(code));
    }
}
