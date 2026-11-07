package com.english9x.platform.jobs;

import com.english9x.platform.jobs.api.JobExecutor;
import com.english9x.platform.jobs.api.JobHandler;
import java.util.HashMap;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import org.springframework.context.ApplicationContext;

/**
 * Maps job types to their {@link JobHandler} beans. Built once at startup so that a duplicate handler, a bean that is
 * not a {@link JobExecutor}, an invalid type name or a malformed retry-delays value stops the application then.
 */
class HandlerRegistry {

    private final Map<String, JobExecutor> byType = new HashMap<>();

    HandlerRegistry(ApplicationContext context, RetryPolicy retryPolicy) {
        retryPolicy.maxRun("any"); // fail now on a malformed e9x.jobs.max-run, not one poll later
        Map<String, String> beanOfType = new HashMap<>();
        for (Map.Entry<String, Object> e : context.getBeansWithAnnotation(JobHandler.class).entrySet()) {
            String bean = e.getKey();
            JobHandler annotation = context.findAnnotationOnBean(bean, JobHandler.class);
            String type = JobTypes.validate(annotation.value());
            if (!(e.getValue() instanceof JobExecutor executor)) {
                throw new IllegalStateException("bean '" + bean + "' is a @JobHandler(\"" + type
                        + "\") but does not implement JobExecutor");
            }
            String other = beanOfType.put(type, bean);
            if (other != null) {
                throw new IllegalStateException("more than one handler for job type '" + type + "': beans '" + other
                        + "' and '" + bean + "'");
            }
            retryPolicy.delays(type); // fail now on a malformed e9x.jobs.<type>.retry-delays
            retryPolicy.maxRun(type); // and e9x.jobs.<type>.max-run
            byType.put(type, executor);
        }
    }

    Optional<JobExecutor> find(String type) {
        return Optional.ofNullable(byType.get(type));
    }

    Set<String> types() {
        return Set.copyOf(byType.keySet());
    }
}
