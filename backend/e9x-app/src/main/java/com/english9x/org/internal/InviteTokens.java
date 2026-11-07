package com.english9x.org.internal;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.security.SecureRandom;
import java.util.Base64;

/** Invitation tokens: 256 random bits as base64url, stored only as their SHA-256 digest (same scheme as the identity tokens). */
final class InviteTokens {

    private static final SecureRandom RANDOM = new SecureRandom();

    private InviteTokens() {}

    static String newToken() {
        byte[] b = new byte[32];
        RANDOM.nextBytes(b);
        return Base64.getUrlEncoder().withoutPadding().encodeToString(b);
    }

    static byte[] hash(String token) {
        try {
            return MessageDigest.getInstance("SHA-256").digest(token.getBytes(StandardCharsets.UTF_8));
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }

    /** A string that cannot be a real token is rejected before the database is asked. */
    static boolean plausible(String token) {
        return token != null && token.length() >= 40 && token.length() <= 64 && token.matches("[A-Za-z0-9_-]+");
    }
}
