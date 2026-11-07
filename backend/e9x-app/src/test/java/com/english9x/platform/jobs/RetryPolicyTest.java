package com.english9x.platform.jobs;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.time.Duration;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.mock.env.MockEnvironment;

/** Retry delays resolve per job type; ai_grade resolves to the spec §15.1 key without duplicating its value. */
class RetryPolicyTest {

    @Test
    void aMaxRunMustBeAPositiveDuration() {
        assertThrows(IllegalArgumentException.class, () -> new RetryPolicy(new MockEnvironment().withProperty("e9x.jobs.max-run", "soon")).maxRun("x"));
        assertThrows(IllegalArgumentException.class, () -> new RetryPolicy(new MockEnvironment().withProperty("e9x.jobs.max-run", "0s")).maxRun("x"));
        assertThrows(IllegalArgumentException.class, () -> new RetryPolicy(new MockEnvironment().withProperty("e9x.jobs.x.max-run", "-1m")).maxRun("x"));
        assertEquals(Duration.ofMinutes(5), new RetryPolicy(new MockEnvironment().withProperty("e9x.jobs.x.max-run", "5m")).maxRun("x"));
    }

    @Test
    void aTypeSpecificKeyWins() {
        RetryPolicy p = new RetryPolicy(new MockEnvironment().withProperty("e9x.jobs.excel_import.retry-delays", "5s,1m"));
        assertEquals(List.of(Duration.ofSeconds(5), Duration.ofMinutes(1)), p.delays("excel_import"));
        assertEquals(3, p.maxAttempts("excel_import"), "retries are attempts after the first");
    }

    @Test
    void aiGradeResolvesToTheGradingKey() {
        RetryPolicy p = new RetryPolicy(new MockEnvironment().withProperty("e9x.grading.ai.retry-delays", "30s,2m,10m"));
        assertEquals(List.of(Duration.ofSeconds(30), Duration.ofMinutes(2), Duration.ofMinutes(10)), p.delays("ai_grade"));
        assertEquals(4, p.maxAttempts("ai_grade"));

        RetryPolicy changed = new RetryPolicy(new MockEnvironment().withProperty("e9x.grading.ai.retry-delays", "1s"));
        assertEquals(List.of(Duration.ofSeconds(1)), changed.delays("ai_grade"), "the §15.1 key is the single source");
    }

    @Test
    void theGeneralKeyOverridesTheAlias() {
        RetryPolicy p = new RetryPolicy(new MockEnvironment()
                .withProperty("e9x.grading.ai.retry-delays", "30s")
                .withProperty("e9x.jobs.ai_grade.retry-delays", "7s"));
        assertEquals(List.of(Duration.ofSeconds(7)), p.delays("ai_grade"));
    }

    @Test
    void otherTypesFallBackToTheDefaultKeyThenToBuiltInDefaults() {
        RetryPolicy configured = new RetryPolicy(new MockEnvironment().withProperty("e9x.jobs.default-retry-delays", "4s,8s"));
        assertEquals(List.of(Duration.ofSeconds(4), Duration.ofSeconds(8)), configured.delays("send_notification"));
        RetryPolicy bare = new RetryPolicy(new MockEnvironment());
        assertEquals(List.of(Duration.ofSeconds(30), Duration.ofMinutes(2), Duration.ofMinutes(10)), bare.delays("send_notification"));
    }

    @Test
    void anEmptyValueMeansNoRetries() {
        RetryPolicy p = new RetryPolicy(new MockEnvironment().withProperty("e9x.jobs.expire_orders.retry-delays", ""));
        assertTrue(p.delays("expire_orders").isEmpty());
        assertEquals(1, p.maxAttempts("expire_orders"));
    }

    @Test
    void aMalformedDelayIsRejectedNotIgnored() {
        RetryPolicy p = new RetryPolicy(new MockEnvironment().withProperty("e9x.jobs.x_type.retry-delays", "30s,soon"));
        assertThrows(IllegalArgumentException.class, () -> p.delays("x_type"));
    }
}
