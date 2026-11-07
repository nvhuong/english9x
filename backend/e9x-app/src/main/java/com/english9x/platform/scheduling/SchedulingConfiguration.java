package com.english9x.platform.scheduling;

import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.annotation.EnableScheduling;

/**
 * Turns on {@code @Scheduled} for the whole application. It lives in the platform, not in a business module: scheduled work
 * (account housekeeping, event redelivery, later the periodic jobs) must not depend on which module happens to enable it.
 */
@Configuration(proxyBeanMethods = false)
@EnableScheduling
class SchedulingConfiguration {}
