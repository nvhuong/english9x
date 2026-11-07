package com.english9x.platform.events;

import com.english9x.org.api.CurrentContextHolder;
import org.springframework.core.task.TaskDecorator;
import org.springframework.stereotype.Component;

/**
 * Every {@code @Async} task (so every {@code @ApplicationModuleListener}, which runs after the publisher's commit on a pool thread) starts
 * and ends with NO user/org binding on its thread. A listener has no request, so it never inherits someone's context by accident, and a
 * listener that binds one (to read data as a user) cannot leave it for the next task on that pool thread. A task that needs a context
 * binds it itself, before it opens its transaction. Spring Boot applies this to the auto-configured task executor.
 */
@Component
class AsyncContextCleaner implements TaskDecorator {

    @Override
    public Runnable decorate(Runnable task) {
        return () -> {
            CurrentContextHolder.clear();
            try {
                task.run();
            } finally {
                CurrentContextHolder.clear();
            }
        };
    }
}
