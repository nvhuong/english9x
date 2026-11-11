package com.english9x.classroom.assignment;

import com.english9x.platform.jobs.api.RecurringJob;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/** The classroom properties and the periodic due-soon job (queued by the platform's scheduler, see RecurringJob). */
@Configuration(proxyBeanMethods = false)
@EnableConfigurationProperties(ClassroomProperties.class)
class ClassroomConfiguration {

    @Bean
    RecurringJob assignmentDueSoonSchedule(ClassroomProperties props) {
        return new RecurringJob("assignment_due_soon", props.jobInterval());
    }
}
