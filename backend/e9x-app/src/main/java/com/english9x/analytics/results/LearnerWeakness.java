package com.english9x.analytics.results;

import java.util.List;
import java.util.UUID;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

/**
 * The narrow internal API of the results screen for the class report (F49): the weakest question types of ANOTHER person, over the attempts made in one organisation. The rates are
 * the ones P4.4 shows the learner (right answers / answered, per question type, worst first); the database function behind it answers only for the organisation's ORG_ADMIN / TEACHER.
 */
@Component
public class LearnerWeakness {

    /** A question type the person gets wrong; {@code rate} = correct / total. */
    public record WeakType(String typeCode, String typeName, int correct, int total, double rate) {}

    private final ResultsRepository repo;

    LearnerWeakness(ResultsRepository repo) {
        this.repo = repo;
    }

    /** At most {@code limit} question types with a right-answer rate below 100%, worst first. Call inside a transaction. */
    @Transactional(readOnly = true)
    public List<WeakType> worst(UUID orgId, UUID userId, int limit) {
        return repo.typeRatesOf(userId, orgId).stream().filter(t -> t.rate() < 1.0).limit(Math.max(0, limit))
                .map(t -> new WeakType(t.typeCode(), t.typeName(), t.correct(), t.total(), t.rate())).toList();
    }
}
