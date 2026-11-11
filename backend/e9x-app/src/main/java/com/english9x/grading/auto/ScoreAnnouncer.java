package com.english9x.grading.auto;

import com.english9x.grading.api.events.AttemptScored;
import com.english9x.grading.auto.GraderStore.Board;
import com.english9x.grading.db.GraderDatabase;
import java.time.Clock;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.Set;
import java.util.UUID;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.stereotype.Component;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * Publishes {@code AttemptScored} once every skill of the attempt has a displayed score. Shared by every scorer (automatic, AI, teacher): whoever
 * writes the last missing score calls it. The grading record is locked while it is published and marked, so concurrent runs queue and the second finds
 * it announced; if the process dies in between, the mark is missing and the next call publishes again (consumers are idempotent, spec events.md).
 * Nothing happens before the automatic scoring wrote the grading record: its own announcement then sees the scores already written.
 */
@Component
public class ScoreAnnouncer {

    private final GraderDatabase grader;
    private final GraderStore store;
    private final TransactionTemplate primary;
    private final ApplicationEventPublisher events;
    private final Clock clock;

    ScoreAnnouncer(GraderDatabase grader, GraderStore store, PlatformTransactionManager transactions, ApplicationEventPublisher events, Clock clock) {
        this.grader = grader;
        this.store = store;
        this.primary = new TransactionTemplate(transactions);
        this.events = events;
        this.clock = clock;
    }

    public void announce(UUID userId, UUID orgId, UUID attemptId) {
        Set<String> required = new LinkedHashSet<>();
        grader.read(userId, orgId, j -> {
            store.sectionSkills(j, attemptId).values().stream().filter(s -> s != null).forEach(required::add);
            return null;
        });
        grader.write(userId, orgId, j -> {
            if (!store.lockUnannounced(j, attemptId)) {
                return null;
            }
            Board board = store.displayed(j, attemptId);
            if (required.isEmpty() || !board.scores().keySet().containsAll(required)) {
                return null; // a skill still waits for its score (a table, the AI, a teacher)
            }
            primary.executeWithoutResult(status -> events.publishEvent(new AttemptScored(attemptId, userId, new LinkedHashMap<>(board.scores()), board.official())));
            store.markAnnounced(j, attemptId, clock.instant());
            return null;
        });
    }
}
