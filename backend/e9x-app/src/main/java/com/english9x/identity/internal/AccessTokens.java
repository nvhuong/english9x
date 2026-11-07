package com.english9x.identity.internal;

import java.time.Clock;
import java.time.Instant;
import java.util.UUID;
import org.springframework.security.oauth2.jose.jws.MacAlgorithm;
import org.springframework.security.oauth2.jwt.JwsHeader;
import org.springframework.security.oauth2.jwt.JwtClaimsSet;
import org.springframework.security.oauth2.jwt.JwtEncoder;
import org.springframework.security.oauth2.jwt.JwtEncoderParameters;
import org.springframework.stereotype.Component;

/**
 * Issues the short-lived access token: {@code sub} = user id, {@code iss}, {@code iat}, {@code exp}, {@code jti} and
 * {@link #CREDENTIALS_VERSION}, the version of the account's credentials it was issued under. The request filter compares that with the
 * database on every authenticated request, so a password reset or a Google link ends every access token at once.
 */
@Component
class AccessTokens {

    static final String CREDENTIALS_VERSION = "credentials_version";

    private final JwtEncoder encoder;
    private final AuthProperties props;
    private final Clock clock;

    AccessTokens(JwtEncoder encoder, AuthProperties props, Clock clock) {
        this.encoder = encoder;
        this.props = props;
        this.clock = clock;
    }

    String issue(UUID userId, long credentialsVersion) {
        Instant now = clock.instant();
        JwtClaimsSet claims = JwtClaimsSet.builder()
                .issuer(props.issuer())
                .subject(userId.toString())
                .issuedAt(now)
                .expiresAt(now.plus(props.accessTokenTtl()))
                .id(UUID.randomUUID().toString())
                .claim(CREDENTIALS_VERSION, credentialsVersion)
                .build();
        return encoder.encode(JwtEncoderParameters.from(JwsHeader.with(MacAlgorithm.HS256).build(), claims)).getTokenValue();
    }
}
