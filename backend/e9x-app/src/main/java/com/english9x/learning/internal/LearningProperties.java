package com.english9x.learning.internal;

import java.time.Duration;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;
import org.springframework.context.annotation.Configuration;

/**
 * {@code e9x.learning.*} (spec 15.1). {@code heartbeatInterval}: how often the browser signals that the learner is studying (read by the client);
 * {@code idleTimeout}: a gap between two signals longer than this is not study time; {@code streak.minMinutes}: the minutes that make a day count in the streak.
 */
@ConfigurationProperties("e9x.learning")
record LearningProperties(
        @DefaultValue("30s") Duration heartbeatInterval,
        @DefaultValue("2m") Duration idleTimeout,
        @DefaultValue Streak streak) {

    record Streak(@DefaultValue("10") int minMinutes) {}

    LearningProperties {
        if (heartbeatInterval.isNegative() || heartbeatInterval.isZero() || idleTimeout.isNegative() || idleTimeout.isZero() || streak.minMinutes() < 0) {
            throw new IllegalStateException("e9x.learning: invalid values");
        }
    }

    @Configuration(proxyBeanMethods = false)
    @EnableConfigurationProperties(LearningProperties.class)
    static class Registration {}
}
