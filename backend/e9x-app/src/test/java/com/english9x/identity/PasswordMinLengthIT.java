package com.english9x.identity;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.english9x.support.SmtpSink;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.springframework.test.context.TestPropertySource;

/** e9x.auth.password-min-length is the rule, wherever a new password is chosen. Configured to 12 here. */
@TestPropertySource(properties = "e9x.auth.password-min-length=12")
class PasswordMinLengthIT extends IdentityIT {

    @Test
    void registrationReadsTheConfiguredMinimum() {
        Res shortOne = register(uniqueEmail(), "12345678901");
        assertEquals(400, shortOne.status(), shortOne.raw());
        assertTrue(shortOne.raw().contains("\"field\":\"password\""), shortOne.raw());
        assertEquals(201, register(uniqueEmail(), "123456789012").status());
    }

    @Test
    void passwordResetReadsTheConfiguredMinimum() {
        String email = registerVerified(uniqueEmail(), "A-long-enough-password");
        int before = smtp().to(email).size();
        post("/api/auth/password-reset/request", Map.of("email", email));
        SmtpSink.Mail mail = smtp().await(email, before);
        String token = mail.linkParam("token");
        Res shortOne = post("/api/auth/password-reset/confirm", Map.of("token", token, "newPassword", "12345678901"));
        assertEquals(400, shortOne.status(), shortOne.raw());
        assertTrue(shortOne.raw().contains("\"field\":\"newPassword\""), shortOne.raw());
        assertEquals(204, post("/api/auth/password-reset/confirm", Map.of("token", token, "newPassword", "123456789012")).status());
    }
}
