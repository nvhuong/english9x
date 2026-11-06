package com.english9x.platform.jobs;

import com.english9x.platform.jobs.api.JobQueue;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.time.Clock;
import com.english9x.platform.jobs.api.JobExecutionListener;
import org.springframework.beans.factory.ObjectProvider;
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
    JobQueue jobQueue(JdbcTemplate jdbc, ObjectMapper mapper, Clock clock) {
        return new JdbcJobQueue(jdbc, mapper, clock);
    }

    /** A web-only instance (worker disabled) still starts handler beans that inject WorkerJdbc; using it fails clearly. */
    @Configuration(proxyBeanMethods = false)
    @ConditionalOnProperty(prefix = "app.worker", name = "enabled", havingValue = "false")
    static class WorkerDisabled {

        @Bean
        com.english9x.platform.jobs.api.WorkerJdbc refusingWorkerJdbc() {
            return new com.english9x.platform.jobs.api.WorkerJdbc() {
                @Override
                public org.springframework.jdbc.core.JdbcTemplate jdbc() {
                    throw refused();
                }

                @Override
                public org.springframework.transaction.support.TransactionTemplate transaction() {
                    throw refused();
                }

                private IllegalStateException refused() {
                    return new IllegalStateException("WorkerJdbc is unavailable: app.worker.enabled=false on this instance");
                }
            };
        }
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
                JobsProperties properties, Clock clock, ObjectProvider<JobExecutionListener> listeners) {
            return new JobWorker(store, handlers, retryPolicy, database, properties, clock,
                    listeners.orderedStream().toList());
        }
    
        @Bean
        JobWorkerHealthIndicator jobWorkerHealthIndicator(JobWorker worker) {
            return new JobWorkerHealthIndicator(worker);
        }
    }
}
