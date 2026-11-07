package com.english9x.platform.events;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.english9x.org.api.CurrentContextHolder;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

/** A pool thread running an async listener starts and ends with no user/org bound, whatever an earlier task left on it. */
class AsyncContextCleanerTest {

    @AfterEach
    void clear() {
        CurrentContextHolder.clear();
    }

    @Test
    void aLeftoverBindingIsNotSeenByTheTask() {
        CurrentContextHolder.bind(UUID.randomUUID(), UUID.randomUUID()); // what a leaky earlier task would have left
        AtomicReference<Boolean> boundInside = new AtomicReference<>();
        new AsyncContextCleaner().decorate(() -> boundInside.set(CurrentContextHolder.current().isPresent())).run();
        assertEquals(false, boundInside.get());
    }

    @Test
    void aBindingTheTaskMakesIsNotLeftForTheNextOne() {
        new AsyncContextCleaner().decorate(() -> CurrentContextHolder.bind(UUID.randomUUID(), null)).run();
        assertTrue(CurrentContextHolder.current().isEmpty());
    }

    @Test
    void anExceptionStillCleansUp() {
        try {
            new AsyncContextCleaner().decorate(() -> {
                CurrentContextHolder.bind(UUID.randomUUID(), null);
                throw new IllegalStateException("boom");
            }).run();
        } catch (IllegalStateException expected) {
            // expected
        }
        assertTrue(CurrentContextHolder.current().isEmpty());
    }
}
