package com.english9x.support;

import com.nimbusds.jose.JWSAlgorithm;
import com.nimbusds.jose.JWSHeader;
import com.nimbusds.jose.crypto.MACSigner;
import com.nimbusds.jwt.JWTClaimsSet;
import com.nimbusds.jwt.SignedJWT;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.util.Date;
import java.util.UUID;
import org.springframework.test.context.DynamicPropertyRegistry;

/** Auth settings every integration test needs, plus helpers to mint access tokens the way the application does. */
public final class TestAuth {

    public static final String JWT_SECRET = "test-only-secret-test-only-secret-0123456789";
    public static final String ISSUER = "english9x";
    public static final String BASE_URL = "https://e9x.test";
    public static final SmtpSink SMTP = new SmtpSink();

    private TestAuth() {}

    public static void configure(DynamicPropertyRegistry registry) {
        registry.add("e9x.auth.jwt-secret", () -> JWT_SECRET);
        registry.add("e9x.public-base-url", () -> BASE_URL);
        registry.add("e9x.auth.bcrypt-cost", () -> "4");
        registry.add("e9x.auth.register-rate-limit", () -> "1000/1h");
        registry.add("spring.mail.host", () -> "localhost");
        registry.add("spring.mail.port", () -> String.valueOf(SMTP.port()));
    }

    /** The version of a fresh account (users.credentials_version defaults to 1). */
    public static final long FRESH_CREDENTIALS_VERSION = 1;

    /** A valid 15-minute access token for {@code userId}, signed with the test secret, for an account whose credentials never changed. */
    public static String accessToken(UUID userId) {
        return accessToken(userId, FRESH_CREDENTIALS_VERSION);
    }

    public static String accessToken(UUID userId, long credentialsVersion) {
        return signed(userId.toString(), ISSUER, Instant.now().plus(Duration.ofMinutes(15)), JWSAlgorithm.HS256, JWT_SECRET, credentialsVersion);
    }

    /** A token as issued before access tokens carried the credentials version: otherwise perfectly valid. */
    public static String accessTokenWithoutCredentialsVersion(UUID userId) {
        return signed(userId.toString(), ISSUER, Instant.now().plus(Duration.ofMinutes(15)), JWSAlgorithm.HS256, JWT_SECRET, null);
    }

    public static String token(String subject, String issuer, Instant expires, JWSAlgorithm alg, String secret) {
        return signed(subject, issuer, expires, alg, secret, FRESH_CREDENTIALS_VERSION);
    }

    private static String signed(String subject, String issuer, Instant expires, JWSAlgorithm alg, String secret, Long credentialsVersion) {
        try {
            JWTClaimsSet.Builder b = new JWTClaimsSet.Builder().subject(subject).issuer(issuer)
                    .issueTime(Date.from(Instant.now().minusSeconds(1))).expirationTime(Date.from(expires));
            if (credentialsVersion != null) {
                b.claim("credentials_version", credentialsVersion);
            }
            JWTClaimsSet claims = b.build();
            SignedJWT jwt = new SignedJWT(new JWSHeader(alg), claims);
            jwt.sign(new MACSigner(secret.getBytes(StandardCharsets.UTF_8)));
            return jwt.serialize();
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }

    /** {@code alg: none}: header and claims, empty signature. */
    public static String unsignedToken(String subject) {
        java.util.Base64.Encoder b = java.util.Base64.getUrlEncoder().withoutPadding();
        String header = b.encodeToString("{\"alg\":\"none\"}".getBytes(StandardCharsets.UTF_8));
        String exp = Long.toString(Instant.now().plusSeconds(900).getEpochSecond());
        String body = b.encodeToString(("{\"sub\":\"" + subject + "\",\"iss\":\"" + ISSUER + "\",\"exp\":" + exp + "}").getBytes(StandardCharsets.UTF_8));
        return header + "." + body + ".";
    }
}
