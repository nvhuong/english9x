package com.english9x.platform.jobs.api;

import java.lang.annotation.Documented;
import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;
import org.springframework.stereotype.Component;

/**
 * Marks a Spring bean implementing {@link JobExecutor} as the handler of one job type. Two handlers for the same type,
 * or a bean that is not a {@link JobExecutor}, fail startup.
 *
 * <p>Being a {@link Component}, the bean is named after its type (Spring uses a stereotype's {@code value} as the bean
 * name), which also makes a second handler for the same type a bean-name clash at startup.
 */
@Target(ElementType.TYPE)
@Retention(RetentionPolicy.RUNTIME)
@Documented
@Component
public @interface JobHandler {

    /** The job type, e.g. {@code ai_grade}. */
    String value();
}
