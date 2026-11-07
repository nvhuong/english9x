package com.english9x.notification.internal;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.servlet.ServletInputStream;
import java.io.BufferedReader;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.web.ErrorResponseException;

/** The webhook checks the secret BEFORE it reads the body: a caller without the secret cannot make the server buffer anything. */
class TelegramWebhookControllerTest {

    /** Counts every attempt to get at the body. */
    static final class WatchedRequest extends MockHttpServletRequest {
        final AtomicInteger touched = new AtomicInteger();

        @Override
        public ServletInputStream getInputStream() {
            touched.incrementAndGet();
            throw new AssertionError("the body was opened");
        }

        @Override
        public BufferedReader getReader() {
            touched.incrementAndGet();
            throw new AssertionError("the body was opened");
        }
    }

    private static TelegramWebhookController controller(String secret) {
        NotificationProperties props = new NotificationProperties("a <a@example.test>",
                new NotificationProperties.Telegram("http://localhost:1", "t", "bot", secret, java.time.Duration.ofMinutes(15)));
        return new TelegramWebhookController(props, null, null, null, new ObjectMapper());
    }

    @Test
    void aWrongOrMissingSecretIsRefusedWithoutOpeningTheBody() {
        for (String given : new String[] {null, "", "wrong", "right-but-longer"}) {
            WatchedRequest request = new WatchedRequest();
            ErrorResponseException refused = assertThrows(ErrorResponseException.class, () -> controller("right").receive(given, request));
            assertEquals(401, refused.getStatusCode().value());
            assertEquals(0, request.touched.get(), "body opened for secret " + given);
        }
    }

    @Test
    void anUnconfiguredSecretRefusesEverythingWithoutOpeningTheBody() {
        WatchedRequest request = new WatchedRequest();
        assertThrows(ErrorResponseException.class, () -> controller("").receive("", request));
        assertEquals(0, request.touched.get());
    }
}
