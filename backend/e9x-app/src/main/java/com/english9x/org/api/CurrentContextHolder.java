package com.english9x.org.api;

import java.util.Optional;
import java.util.UUID;
import org.springframework.transaction.support.TransactionSynchronizationManager;

/**
 * Binds the acting user and organisation to the current thread for a scope of work. The request layer (P1.5) binds
 * it from the authenticated user and the validated organisation context; tests and background jobs bind it directly.
 * Every database transaction started while a binding is active runs under that user/org with Row-Level Security.
 * With no binding the transaction runs with an empty context, which RLS answers with no rows.
 *
 * <p>Binding a different user/org while a transaction is running is refused ({@link IllegalStateException}).
 *
 * <p>Thread-bound: code that hops threads must bind again on the new thread.
 */
public final class CurrentContextHolder {

    /** The requested acting user and org. Privilege flags are derived from role data, not carried here. */
    public record Binding(UUID userId, Optional<UUID> orgId) {
        public Binding {
            if (userId == null) {
                throw new IllegalArgumentException("userId is required");
            }
            orgId = orgId == null ? Optional.empty() : orgId;
        }
    }

    /** Restores the previous binding on close. */
    public interface Scope extends AutoCloseable {
        @Override
        void close();
    }

    private static final ThreadLocal<Binding> CURRENT = new ThreadLocal<>();

    private CurrentContextHolder() {}

    /** @param orgId the organisation of the context, or {@code null} for the personal / English9x context */
    public static Scope bind(UUID userId, UUID orgId) {
        Binding previous = CURRENT.get();
        Binding next = new Binding(userId, Optional.ofNullable(orgId));
        // The database session context of a live transaction was fixed when it began. Changing the binding now
        // would leave orgId() saying one thing while RLS and the privilege flags still say another.
        if (TransactionSynchronizationManager.isActualTransactionActive() && !next.equals(previous)) {
            throw new IllegalStateException("cannot change the user/org context inside a running transaction: "
                    + "bind it before the transaction starts (current " + previous + ", requested " + next + ")");
        }
        CURRENT.set(next);
        return () -> {
            if (previous == null) {
                CURRENT.remove();
            } else {
                CURRENT.set(previous);
            }
        };
    }

    /** Drops whatever binding the thread holds. For infrastructure that reuses threads (the job worker), not for request code. */
    public static void clear() {
        CURRENT.remove();
    }

    public static Optional<Binding> current() {
        return Optional.ofNullable(CURRENT.get());
    }
}
