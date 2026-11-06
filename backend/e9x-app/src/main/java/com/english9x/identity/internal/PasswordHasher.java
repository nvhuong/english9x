package com.english9x.identity.internal;

import java.nio.charset.StandardCharsets;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.stereotype.Component;

/**
 * bcrypt. bcrypt only looks at the first 72 BYTES, so longer input is refused (validation) instead of silently
 * truncated. {@link #burn(String)} spends the same time as a real check for an account that does not exist.
 */
@Component
class PasswordHasher {

    static final int MAX_BYTES = 72;

    private final BCryptPasswordEncoder encoder;
    private final String dummyHash;

    PasswordHasher(AuthProperties props) {
        this.encoder = new BCryptPasswordEncoder(props.bcryptCost());
        this.dummyHash = encoder.encode("e9x-dummy-password-never-valid");
    }

    String hash(String password) {
        requireBcryptSafe(password);
        return encoder.encode(password);
    }

    boolean matches(String password, String hash) {
        if (hash == null || password == null || password.getBytes(StandardCharsets.UTF_8).length > MAX_BYTES) {
            return false;
        }
        return encoder.matches(password, hash);
    }

    /** Same work as {@link #matches} against a hash that nobody can match; the result is meaningless. */
    void burn(String password) {
        if (password != null && password.getBytes(StandardCharsets.UTF_8).length <= MAX_BYTES) {
            encoder.matches(password, dummyHash);
        }
    }

    private static void requireBcryptSafe(String password) {
        if (password.getBytes(StandardCharsets.UTF_8).length > MAX_BYTES) {
            throw new IllegalArgumentException("password longer than 72 bytes");
        }
    }
}
