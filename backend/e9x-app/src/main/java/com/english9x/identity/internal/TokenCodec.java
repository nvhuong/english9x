package com.english9x.identity.internal;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.security.SecureRandom;
import java.util.Base64;

/**
 * Opaque tokens (refresh, email verification, password reset, one-time login code): 256 random bits, handed out as
 * base64url, stored only as their SHA-256 digest. A digest is enough (no salt / slow hash) because the input is
 * uniformly random, not a human secret. Comparisons of secrets use {@link MessageDigest#isEqual} (constant time).
 */
final class TokenCodec {

    private static final SecureRandom RANDOM = new SecureRandom();
    private static final Base64.Encoder B64 = Base64.getUrlEncoder().withoutPadding();

    private TokenCodec() {}

    static String newToken() {
        byte[] b = new byte[32];
        RANDOM.nextBytes(b);
        return B64.encodeToString(b);
    }

    static byte[] hash(String token) {
        try {
            return MessageDigest.getInstance("SHA-256").digest(token.getBytes(StandardCharsets.UTF_8));
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }

    /** Constant-time equality of two digests. */
    static boolean same(byte[] a, byte[] b) {
        return a != null && b != null && MessageDigest.isEqual(a, b);
    }

    /** A token that cannot be a real one (wrong shape) is rejected before touching the database. */
    static boolean plausible(String token) {
        return token != null && token.length() >= 40 && token.length() <= 64 && token.matches("[A-Za-z0-9_-]+");
    }
}
