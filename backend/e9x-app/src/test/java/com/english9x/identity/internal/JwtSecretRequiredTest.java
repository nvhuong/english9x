package com.english9x.identity.internal;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.time.Clock;
import org.junit.jupiter.api.Test;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Import;
import org.springframework.security.oauth2.jwt.JwtDecoder;

/** No production default for the signing key: without a good one the application does not start. */
class JwtSecretRequiredTest {

    @Configuration
    static class ClockConfig {
        @Bean
        Clock clock() {
            return Clock.systemUTC();
        }
    }

    private final ApplicationContextRunner runner = new ApplicationContextRunner().withUserConfiguration(ClockConfig.class, JwtConfiguration.class);

    @Test
    void missingSecretStopsStartup() {
        // (the build supplies a test secret as a system property, so "missing" is shown at both ends: the properties
        // object refuses null, and an empty value, which is what an unset E9X_JWT_SECRET becomes, stops the context)
        IllegalStateException e = org.junit.jupiter.api.Assertions.assertThrows(IllegalStateException.class,
                () -> new AuthProperties(null, "english9x", java.time.Duration.ofMinutes(15), java.time.Duration.ofDays(30),
                        java.time.Duration.ofSeconds(10), "10/15m", "3/1h", "5/1h", "20/1h", java.time.Duration.ofHours(24),
                        java.time.Duration.ofHours(1), java.time.Duration.ofSeconds(60), java.time.Duration.ofMinutes(15), 4, 8, java.util.List.of(), "x <x@x.test>", null));
        assertTrue(e.getMessage().contains("E9X_JWT_SECRET"), e.getMessage());
        runner.withPropertyValues("e9x.auth.jwt-secret=").run(ctx -> {
            assertNotNull(ctx.getStartupFailure(), "the context must fail to start");
            assertTrue(rootMessage(ctx.getStartupFailure()).contains("e9x.auth.jwt-secret"), rootMessage(ctx.getStartupFailure()));
        });
    }

    @Test
    void blankAndShortSecretsStopStartup() {
        for (String secret : new String[] {"", "   ", "too-short"}) {
            runner.withPropertyValues("e9x.auth.jwt-secret=" + secret).run(ctx -> assertNotNull(ctx.getStartupFailure(), "'" + secret + "'"));
        }
    }

    @Test
    void aStrongSecretStarts() {
        runner.withPropertyValues("e9x.auth.jwt-secret=0123456789abcdef0123456789abcdef").run(ctx -> {
            assertEquals(null, ctx.getStartupFailure());
            assertNotNull(ctx.getBean(JwtDecoder.class));
        });
    }

    private static String rootMessage(Throwable t) {
        StringBuilder sb = new StringBuilder();
        for (Throwable c = t; c != null; c = c.getCause()) {
            sb.append(c.getMessage()).append(" | ");
        }
        return sb.toString();
    }
}
