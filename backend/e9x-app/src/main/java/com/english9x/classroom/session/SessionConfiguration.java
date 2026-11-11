package com.english9x.classroom.session;

import com.english9x.classroom.assignment.ClassroomProperties;
import com.english9x.platform.jobs.api.RecurringJob;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/** The periodic session-reminder job (queued by the platform's scheduler, see RecurringJob). The classroom properties are enabled by the assignment side. */
@Configuration(proxyBeanMethods = false)
class SessionConfiguration {

    @Bean
    RecurringJob sessionReminderSchedule(ClassroomProperties props) {
        return new RecurringJob("session_reminder", props.sessionJobInterval());
    }
}
