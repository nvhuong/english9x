package com.english9x.identity;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.english9x.support.SmtpSink;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;

/** Runs every auth flow, success and failure, and checks that no secret reached the log output. */
@ExtendWith(OutputCaptureExtension.class)
class NoSecretsInLogsIT extends IdentityIT {

    @Test
    void passwordsTokensAndJwtsNeverAppearInLogs(CapturedOutput output) {
        String email = uniqueEmail();
        String password = "Sup3r-secret-Pass-" + System.nanoTime();
        String newPassword = "Even-newer-secret-" + System.nanoTime();

        SmtpSink.Mail verification = registerUnverified(email, password);
        String verifyToken = verification.linkParam("token");
        assertEquals(204, post("/api/auth/verify-email", Map.of("token", verifyToken)).status());
        assertEquals(401, login(email, "wrong-" + password).status());
        Res ok = login(email, password);
        String access = ok.body().get("accessToken").asText();
        String refresh = ok.body().get("refreshToken").asText();
        Res rotated = post("/api/auth/refresh", Map.of("refreshToken", refresh));
        String refresh2 = rotated.body().get("refreshToken").asText();
        su.update("update refresh_tokens set rotated_at = now() - interval '5 minutes' where rotated_at is not null and user_id = ?", userId(email));
        assertEquals(401, post("/api/auth/refresh", Map.of("refreshToken", refresh)).status()); // reuse: logs a warning
        post("/api/auth/refresh", Map.of("refreshToken", "garbage-garbage-garbage-garbage-garbage-garbage"));
        post("/api/auth/register", Map.of("email", email, "password", "Dup-secret-pass-1", "displayName", "x", "consent", true));
        post("/api/auth/register", Map.of("email", "bad", "password", "Bad-secret-pass-1", "displayName", "x", "consent", true));
        post("/api/auth/password-reset/request", Map.of("email", email));
        SmtpSink.Mail reset = smtp().awaitSubject(email, "Đặt lại mật khẩu");
        String resetToken = reset.linkParam("token");
        assertEquals(204, post("/api/auth/password-reset/confirm", Map.of("token", resetToken, "newPassword", newPassword)).status());
        get("/api/auth/me", "x.y.z");
        get("/api/auth/me", access);
        smtp().setDown(true);
        try {
            register(uniqueEmail(), "Mail-down-secret-1");
            smtp().countAfterSettling("nobody@example.test");
        } finally {
            smtp().setDown(false);
        }

        String logs = output.getAll();
        assertTrue(logs.contains("refresh token reuse detected"), "the scenario really produced the security warning");
        for (String secret : new String[] {password, "wrong-" + password, newPassword, "Dup-secret-pass-1", "Bad-secret-pass-1",
                "Mail-down-secret-1", verifyToken, resetToken, refresh, refresh2, access}) {
            assertFalse(logs.contains(secret), "a secret leaked into the log: " + secret.substring(0, 12) + "...");
        }
        assertFalse(logs.contains("eyJ"), "no JWT in the log");
        assertFalse(logs.contains("$2a$") || logs.contains("$2b$"), "no password hash in the log");
    }
}
