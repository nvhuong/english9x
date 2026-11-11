package com.english9x.grading.teacher;

import com.english9x.platform.jobs.api.RecurringJob;
import java.time.Duration;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/** The periodic job of the teachers' queue (queued by the platform's scheduler, see RecurringJob). */
@Configuration(proxyBeanMethods = false)
class TeacherConfiguration {

    @Bean
    RecurringJob releaseExpiredClaimSchedule(@Value("${e9x.grading.claim-job-interval:5m}") Duration every) {
        return new RecurringJob("release_expired_claim", every);
    }
}
