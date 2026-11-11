package com.english9x.classroom.assignment;

import java.time.Duration;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

/**
 * Spec section 15.1, {@code e9x.classroom.*}.
 *
 * @param dueSoon     how long before an assignment is due the learner who has not handed it in is told ({@code ASSIGNMENT_DUE_SOON})
 * @param jobInterval period of the {@code assignment_due_soon} job
 * @param sessionJobInterval period of the {@code session_reminder} job; it tells everybody whose session starts within
 *                           {@code e9x.notification.session-reminder} (1h), so the notice reaches them between 1h and 1h minus one period before the start
 */
@ConfigurationProperties("e9x.classroom")
public record ClassroomProperties(@DefaultValue("24h") Duration dueSoon, @DefaultValue("1h") Duration jobInterval,
        @DefaultValue("5m") Duration sessionJobInterval) {}
