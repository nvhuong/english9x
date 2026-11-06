package com.english9x.platform.jobs;

import com.english9x.platform.jobs.api.JobQueue;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.time.Clock;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.ApplicationContext;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.env.Environment;
import org.springframework.jdbc.core.JdbcTemplate;

@Configuration(proxyBeanMethods = false)
@EnableConfigurationProperties(JobsProperties.class)
class JobsConfiguration {

    @Bean
    @ConditionalOnMissingBean(Clock.class)
    Clock jobsClock() {
        return Clock.systemUTC();
    }

    @Bean
    JobQueue jobQueue(JdbcTemplate jdbc, ObjectMapper mapper, Clock clock) {
        return new JdbcJobQueue(jdbc, mapper, clock);
    }

    /** Everything that exists only in an instance that runs background jobs ({@code app.worker.enabled}). */
    @Configuration(proxyBeanMethods = false)
    @ConditionalOnProperty(prefix = "app.worker", name = "enabled", havingValue = "true", matchIfMissing = true)
    @EnableConfigurationProperties(WorkerDataSourceProperties.class)
    static class Worker {

        @Bean
        WorkerDatabase workerDatabase(WorkerDataSourceProperties properties) {
            return new WorkerDatabase(properties);
        }

        @Bean
        RetryPolicy retryPolicy(Environment environment) {
            return new RetryPolicy(environment);
        }

        @Bean
        HandlerRegistry handlerRegistry(ApplicationContext context, RetryPolicy retryPolicy) {
            return new HandlerRegistry(context, retryPolicy);
        }

        @Bean
        JobStore jobStore(WorkerDatabase database, ObjectMapper mapper, JobsProperties properties) {
            return new JobStore(database.raw(), mapper, properties.lease());
        }

        @Bean
        JobWorker jobWorker(JobStore store, HandlerRegistry handlers, RetryPolicy retryPolicy, WorkerDatabase database,
                JobsProperties properties, Clock clock) {
            return new JobWorker(store, handlers, retryPolicy, database, properties, clock);
        }
    }
}
