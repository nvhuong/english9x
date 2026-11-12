package com.english9x.grading.auto;

import com.english9x.catalog.api.QuestionTypeDef;
import com.english9x.catalog.api.QuestionTypes;
import com.english9x.domain.scoring.AnswerKey;
import com.english9x.domain.scoring.ItemResult;
import com.english9x.domain.scoring.ObjectiveScorers;
import com.english9x.grading.api.AttemptSolutions;
import com.english9x.grading.auto.GraderStore.Attempt;

import com.english9x.grading.db.GraderDatabase;
import com.fasterxml.jackson.databind.JsonNode;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import org.springframework.stereotype.Component;

/**
 * The solution side of a scored attempt, read on the grader's connection in the context of the person and organisation the caller names ({@link GraderDatabase}): the attempt
 * must be there for that pair, SUBMITTED, and graded ({@code attempt_gradings} exists, which only happens after the late-sync grace). Anything else answers empty, so a result
 * page asked about an attempt that is still going, or still inside the grace, is given no key, no explanation and no transcript. Right or wrong comes from the scorer the score
 * of the attempt came from ({@link ObjectiveScorers}), never from a second rule.
 */
@Component
class AttemptSolutionsImpl implements AttemptSolutions {

    private final GraderDatabase grader;
    private final GraderStore store;
    private final QuestionTypes types;

    AttemptSolutionsImpl(GraderDatabase grader, GraderStore store, QuestionTypes types) {
        this.grader = grader;
        this.store = store;
        this.types = types;
    }

    @Override
    public Optional<List<AttemptSolutions.Item>> of(UUID attemptId, UUID userId, UUID orgId) {
        if (attemptId == null || userId == null) {
            return Optional.empty();
        }
        return grader.read(userId, orgId, j -> {
            Optional<Attempt> attempt = store.attempt(j, attemptId);
            if (attempt.isEmpty() || !store.graded(j, attemptId)) {
                return Optional.<List<AttemptSolutions.Item>>empty();
            }
            Attempt a = attempt.get();
            Map<String, String> skillOfSection = store.sectionSkills(j, attemptId);
            List<AttemptSolutions.Item> out = new ArrayList<>();
            for (GraderStore.Item item : store.items(j, attemptId, a.testVersionId())) {
                String skill = skillOfSection.get(item.sectionCode());
                if (skill == null) {
                    continue; // a section this attempt never held
                }
                out.add(toItem(item, skill));
            }
            return Optional.of(out);
        });
    }

    private AttemptSolutions.Item toItem(GraderStore.Item item, String skill) {
        QuestionTypeDef type = types.get(item.typeCode());
        JsonNode transcript = item.key() == null ? null : item.key().path("transcript");
        String text = transcript != null && transcript.isTextual() ? transcript.asText() : null;
        if (!type.kind().autoScored()) {
            return new AttemptSolutions.Item(item.questionId(), item.versionId(), item.sectionCode(), skill, item.typeCode(), false, false, 0, item.points(), item.answer(), null,
                    item.explanation(), text);
        }
        AnswerKey key = AnswerMapper.key(item.key(), item.points(), item.versionId());
        ItemResult result = ObjectiveScorers.forKind(type.kind()).score(key, AnswerMapper.answer(item.answer()));
        return new AttemptSolutions.Item(item.questionId(), item.versionId(), item.sectionCode(), skill, item.typeCode(), true, result.correct(), result.earned(), result.max(),
                item.answer(), item.key().path("slots"), item.explanation(), text);
    }
}
