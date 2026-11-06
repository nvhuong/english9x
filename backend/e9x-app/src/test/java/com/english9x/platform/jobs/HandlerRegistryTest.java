package com.english9x.platform.jobs;

import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.english9x.platform.jobs.api.JobExecutor;
import com.english9x.platform.jobs.api.JobHandler;
import com.english9x.platform.jobs.api.JobResult;
import org.junit.jupiter.api.Test;
import org.springframework.context.annotation.AnnotationConfigApplicationContext;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.mock.env.MockEnvironment;

/** A wrong handler set must stop the application at startup, not show up as stuck jobs. */
class HandlerRegistryTest {

    @JobHandler("same_type")
    static class First implements JobExecutor {
        public JobResult handle(com.english9x.platform.jobs.api.Job job) {
            return JobResult.success();
        }
    }

    @JobHandler("same_type")
    static class Second implements JobExecutor {
        public JobResult handle(com.english9x.platform.jobs.api.Job job) {
            return JobResult.success();
        }
    }

    @JobHandler("not_an_executor")
    static class NotAnExecutor {}

    @JobHandler("Bad Type!")
    static class BadType implements JobExecutor {
        public JobResult handle(com.english9x.platform.jobs.api.Job job) {
            return JobResult.success();
        }
    }

    @Configuration
    static class Infra {
        @Bean
        RetryPolicy retryPolicy() {
            return new RetryPolicy(new MockEnvironment());
        }

        @Bean
        HandlerRegistry registry(org.springframework.context.ApplicationContext ctx, RetryPolicy p) {
            return new HandlerRegistry(ctx, p);
        }
    }

    /** Beans get explicit names: a @JobHandler("x") is a @Component whose value Spring would otherwise use as the bean name. */
    private static AnnotationConfigApplicationContext context(Class<?>... beans) {
        AnnotationConfigApplicationContext ctx = new AnnotationConfigApplicationContext();
        ctx.register(Infra.class);
        for (int i = 0; i < beans.length; i++) {
            ctx.registerBean("handler" + i, beans[i]);
        }
        return ctx;
    }

    private static Throwable startup(Class<?>... beans) {
        return assertThrows(RuntimeException.class, () -> {
            try (var ctx = context(beans)) {
                ctx.refresh();
            }
        });
    }

    private static String chain(Throwable t) {
        StringBuilder sb = new StringBuilder();
        for (; t != null; t = t.getCause()) {
            sb.append(t.getMessage()).append(" | ");
        }
        return sb.toString();
    }

    @Test
    void twoHandlersForOneTypeFailStartup() {
        String message = chain(startup(First.class, Second.class));
        assertTrue(message.contains("same_type") && message.contains("more than one"), message);
    }

    @Test
    void aHandlerBeanThatIsNotAnExecutorFailsStartup() {
        String message = chain(startup(NotAnExecutor.class));
        assertTrue(message.contains("not_an_executor") && message.contains("JobExecutor"), message);
    }

    @Test
    void anInvalidTypeNameFailsStartup() {
        String message = chain(startup(BadType.class));
        assertTrue(message.contains("Bad Type!"), message);
    }

    @Test
    void oneHandlerPerTypeStarts() {
        try (var ctx = context(First.class)) {
            ctx.refresh();
            assertTrue(ctx.getBean(HandlerRegistry.class).find("same_type").isPresent());
            assertTrue(ctx.getBean(HandlerRegistry.class).find("other").isEmpty());
        }
    }
}
