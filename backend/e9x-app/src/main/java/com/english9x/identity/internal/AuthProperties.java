package com.english9x.identity.internal;

import java.time.Duration;
import java.util.List;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

/**
 * {@code e9x.auth.*} (spec §15.1 keys plus the identity settings of P1.4).
 *
 * @param jwtSecret      HS256 signing key, at least 32 bytes. NO default: the application does not start without it.
 * @param loginRateLimit failed logins allowed per IP + email, "10/15m" (count/window)
 * @param resendRateLimit / resetRateLimit / registerRateLimit requests allowed per IP + email (register: per IP)
 * @param trustedProxies CIDRs of reverse proxies whose X-Forwarded-For is believed (empty: the header is ignored)
 */
@ConfigurationProperties("e9x.auth")
public record AuthProperties(
        String jwtSecret,
        @DefaultValue("english9x") String issuer,
        @DefaultValue("15m") Duration accessTokenTtl,
        @DefaultValue("30d") Duration refreshTokenTtl,
        @DefaultValue("10s") Duration refreshReuseGrace,
        @DefaultValue("10/15m") String loginRateLimit,
        @DefaultValue("3/1h") String resendRateLimit,
        @DefaultValue("5/1h") String resetRateLimit,
        @DefaultValue("20/1h") String registerRateLimit,
        @DefaultValue("24h") Duration verificationTtl,
        @DefaultValue("1h") Duration resetTtl,
        @DefaultValue("60s") Duration oauthCodeTtl,
        @DefaultValue("10") int bcryptCost,
        @DefaultValue("8") int passwordMinLength,
        @DefaultValue({}) List<String> trustedProxies,
        @DefaultValue("English9x <no-reply@english9x.vn>") String mailFrom,
        Google google) {

    public AuthProperties {
        if (jwtSecret == null || jwtSecret.getBytes(java.nio.charset.StandardCharsets.UTF_8).length < 32) {
            throw new IllegalStateException(
                    "e9x.auth.jwt-secret (env E9X_JWT_SECRET) is required and must be at least 32 bytes; refusing to start without it");
        }
        google = google == null ? new Google(null, null, null, null, null, null, null) : google;
    }

    /**
     * Google sign-in. Absent {@code clientId} disables the feature cleanly (the route answers 404, the button is hidden).
     * The endpoint URLs default to Google's and are configurable so E2E can point at a fake OIDC provider.
     */
    public record Google(
            String clientId,
            String clientSecret,
            @DefaultValue("https://accounts.google.com/o/oauth2/v2/auth") String authorizationUri,
            @DefaultValue("https://oauth2.googleapis.com/token") String tokenUri,
            @DefaultValue("https://www.googleapis.com/oauth2/v3/certs") String jwkSetUri,
            @DefaultValue("https://accounts.google.com") String issuer,
            String scopes) {

        public boolean enabled() {
            return clientId != null && !clientId.isBlank() && clientSecret != null && !clientSecret.isBlank();
        }
    }
}
