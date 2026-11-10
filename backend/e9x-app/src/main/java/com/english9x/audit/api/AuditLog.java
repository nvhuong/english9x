package com.english9x.audit.api;

import java.util.Map;
import java.util.UUID;

/**
 * The append-only audit log (spec section 6, F57): who did what, when, in which context.
 *
 * <p>Call {@link #record} from the code that performs the action, <b>inside the same transaction</b>, after the action's own
 * checks have passed and its writes are done. Then the record and the action stand or fall together: if the action rolls back
 * the record goes too, and if the record cannot be written the exception fails the action. It is deliberately not best-effort.
 * A refused attempt (403, 404, 409, validation) is not recorded: throw before recording, or let the transaction roll back.
 *
 * <p>Actor and context are never arguments. The acting person is the user the transaction is bound to
 * ({@code CurrentContextHolder}) and the owner organisation of the record is the bound context; the database fills both from
 * the session and refuses a statement that tries to name others. Consequences, by design:
 * <ul>
 *   <li>With no person bound (a job or a listener that runs without one) {@code record} throws {@link IllegalStateException}.
 *       What the SYSTEM itself changes (a job that expires orders) is recorded through {@link SystemAuditLog} instead, with the actor kind SYSTEM.
 *       Code that records what a PERSON did from a background step binds that person first, as a notification listener already must.
 *   <li>Outside a transaction it throws {@code IllegalTransactionStateException}: a record that is not atomic with its action is a bug.
 * </ul>
 *
 * <p>What goes into {@code detail}: only what is needed to understand the action later, as scalars, lists and small objects
 * (ids, roles, counts, names that are the subject of the action). Never a token, a digest, password material, a Telegram or API
 * secret, nor free text written by a person (a review comment: record its length). The implementation refuses keys that look like
 * those and values that are long text; the total size is bounded by {@code e9x.audit.max-detail-bytes}. An email address is
 * personal data: log one only where the plan says (invitation send and revoke), see the retention note in the data-deletion plan.
 *
 * <p>Naming: {@code action} is lower-case words joined by {@code .} or {@code -} ({@code invitation.send}, {@code review.approve-bundle});
 * {@code targetType} is the kind of thing acted on ({@code user}, {@code organization}, {@code invitation}, {@code review}).
 */
public interface AuditLog {

    /**
     * Records one action as the bound person in the bound context, in the caller's transaction.
     *
     * @param action     what was done, e.g. {@code order.confirm}
     * @param targetType what it was done to, e.g. {@code order}
     * @param targetId   that thing's id, 1 to 200 characters
     * @param detail     small facts about the action; may be null or empty
     * @throws IllegalArgumentException if a name has the wrong shape or the detail holds a forbidden key, a long text, an unsupported value or too much
     * @throws IllegalStateException    if no person is bound to the transaction
     */
    void record(String action, String targetType, String targetId, Map<String, Object> detail);

    default void record(String action, String targetType, UUID targetId, Map<String, Object> detail) {
        record(action, targetType, targetId.toString(), detail);
    }
}
