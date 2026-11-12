package com.english9x.grading.auto;

import com.english9x.catalog.api.QuestionTypeDef;
import com.english9x.catalog.api.QuestionTypes;
import com.english9x.catalog.api.ResolvedScoreTable;
import com.english9x.catalog.api.ScoreFunction;
import com.english9x.catalog.api.ScoreTableNotFoundException;
import com.english9x.catalog.api.ScoreTables;
import com.english9x.domain.scoring.AnswerKey;
import com.english9x.domain.scoring.ItemResult;
import com.english9x.domain.scoring.ObjectiveScorers;
import com.english9x.grading.auto.GraderStore.Attempt;
import com.english9x.grading.auto.GraderStore.Item;
import com.english9x.grading.auto.GraderStore.Row;
import com.english9x.grading.db.GraderDatabase;
import com.english9x.org.api.CurrentContextHolder;
import com.english9x.org.api.OrgContextDeniedException;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Clock;
import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.OptionalDouble;
import java.util.Set;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * Automatic scoring of a submitted attempt (spec 10.1, 10.2, 10.4; F33, F25, F26, F15).
 *
 * <p><b>Nothing the caller says is believed.</b> The ids come from an event or a job payload, which a hijacked request role can forge; they only choose the
 * session context of the grader, whose policies show a row only if it is the SUBMITTED attempt of exactly that person and organisation. Everything that
 * matters (the owner, the exam, the test version, the submission time, the interrupted flag) is read back from that row, and the answers and the keys
 * from the rows that hang on it.
 *
 * <p><b>Not before the late-sync grace.</b> The exam room accepts an answer written before a section ended until {@code e9x.exam.late-sync-grace}
 * after it, also after the submission. No section ends after the attempt was submitted, so no answer can arrive after {@code submittedAt + grace}: scoring
 * waits for that instant ({@link Outcome#GRACE_NOT_OVER} before it) and reads the responses once, after it.
 *
 * <p><b>Idempotent.</b> The grading record of the attempt ({@code attempt_gradings}, primary key = the attempt) arbitrates: the run that inserts it writes the
 * scores, a second run (a redelivered event, a retried job, a concurrent one) finds it and writes nothing. {@code AttemptScored} is announced under a lock
 * on that record and marked, so it is published once; a crash between the scores and the announcement is repaired by the next run (at least once, never
 * skipped).
 *
 * <p><b>No guessing.</b> A skill is converted with the PUBLISHED table {@code ScoreTables#resolve} finds (its version is stored with the score). Where there is
 * none, or the table refuses the raw score, the score row keeps the raw points and no converted score, nothing is announced for it, and the reason is logged.
 * Writing and Speaking are not scored here: their skills have no row until P4.2 / P4.3 write theirs.
 */
@Component
class AutoGrader {

    enum Outcome {
        /** The attempt is not there for this context, is not submitted, or is not one this scorer reads: nothing to do. */
        NOT_ELIGIBLE,
        /** Before {@code submittedAt + late-sync-grace}: try again later. */
        GRACE_NOT_OVER,
        /** This run wrote the scores. */
        GRADED,
        /** An earlier run did; only the announcement (if it was missed) was repeated. */
        ALREADY_GRADED
    }

    private static final Logger LOG = LoggerFactory.getLogger(AutoGrader.class);

    private record Conv(Double scaled, UUID scoreTableVersionId) {
        static final Conv NONE = new Conv(null, null);
    }

    private final GraderDatabase grader;
    private final GraderStore store;
    private final QuestionTypes types;
    private final ScoreTables tables;
    private final TransactionTemplate primary;
    private final ScoreAnnouncer announcer;
    private final Clock clock;
    private final Duration grace;

    AutoGrader(GraderDatabase grader, GraderStore store, QuestionTypes types, ScoreTables tables, PlatformTransactionManager transactions,
            ScoreAnnouncer announcer, Clock clock, @Value("${e9x.exam.late-sync-grace}") Duration grace) {
        this.grader = grader;
        this.store = store;
        this.types = types;
        this.tables = tables;
        this.primary = new TransactionTemplate(transactions);
        this.announcer = announcer;
        this.clock = clock;
        this.grace = grace;
    }

    /** The submitted attempt as the database holds it, for the context the caller names; empty when there is none (a forged or stale hint finds nothing). */
    Optional<Attempt> find(UUID attemptId, UUID userId, UUID orgId) {
        return grader.read(userId, orgId, j -> store.attempt(j, attemptId));
    }

    /** The earliest instant the responses of the attempt are final. */
    java.time.Instant gradeAfter(Attempt a) {
        return a.submittedAt().plus(grace);
    }

    Outcome grade(UUID attemptId, UUID userIdHint, UUID orgIdHint) {
        Optional<Attempt> found = find(attemptId, userIdHint, orgIdHint);
        if (found.isEmpty()) {
            return Outcome.NOT_ELIGIBLE;
        }
        Attempt a = found.get();
        if (clock.instant().isBefore(gradeAfter(a))) {
            return Outcome.GRACE_NOT_OVER;
        }
        boolean wrote = false;
        if (!grader.read(a.userId(), a.orgId(), j -> store.graded(j, a.id()))) {
            Scoring scoring = score(a);
            wrote = grader.write(a.userId(), a.orgId(), j -> {
                if (!store.begin(j, a)) {
                    return false; // a concurrent run was first
                }
                scoring.rows().forEach(r -> store.insertScore(j, a, r));
                scoring.stats().forEach(t -> store.insertStat(j, a, t)); // the statistic of F38 is written with the scores it agrees with
                return true;
            });
        }
        announcer.announce(a.userId(), a.orgId(), a.id());
        return wrote ? Outcome.GRADED : Outcome.ALREADY_GRADED;
    }

    // ---- scoring ----------------------------------------------------------------------------------------------------------------------

    private record Raw(double earned, double max) {}

    /** The score rows of the attempt and, from the same scorer run, one statistic per objective question (the weakness page of F38). */
    private record Scoring(List<Row> rows, List<GraderStore.Stat> stats) {}

    private Scoring score(Attempt a) {
        Map<String, String> skillOfSection = new LinkedHashMap<>();
        List<Item> items = new ArrayList<>();
        String[] variant = new String[1];
        grader.read(a.userId(), a.orgId(), j -> {
            skillOfSection.putAll(store.sectionSkills(j, a.id()));
            items.addAll(store.items(j, a.id(), a.testVersionId()));
            variant[0] = store.variant(j, a.testVersionId());
            return null;
        });

        Map<String, Raw> raw = new LinkedHashMap<>();
        Set<String> notObjective = new LinkedHashSet<>();
        List<GraderStore.Stat> stats = new ArrayList<>();
        for (Item item : items) {
            String skill = skillOfSection.get(item.sectionCode());
            if (skill == null) {
                continue; // a section of the test this attempt did not hold (it was never sat): nothing to score
            }
            QuestionTypeDef type = types.get(item.typeCode());
            if (!type.kind().autoScored()) {
                notObjective.add(skill);
                continue;
            }
            AnswerKey key = AnswerMapper.key(item.key(), item.points(), item.versionId());
            ItemResult result = ObjectiveScorers.forKind(type.kind()).score(key, AnswerMapper.answer(item.answer()));
            raw.merge(skill, new Raw(result.earned(), result.max()), (x, y) -> new Raw(x.earned() + y.earned(), x.max() + y.max()));
            stats.add(new GraderStore.Stat(item.versionId(), skill, item.typeCode(), round(result.earned()), round(result.max()), result.correct()));
        }
        notObjective.forEach(raw::remove); // a skill with a question for a teacher or the AI is not scored here, not even in part

        Map<String, Conv> converted = convert(a, variant[0], raw);
        List<Row> rows = new ArrayList<>();
        raw.forEach((skill, r) -> {
            Conv c = converted.getOrDefault(skill, Conv.NONE);
            rows.add(new Row(skill, round(r.earned()), round(r.max()), c.scaled(), c.scoreTableVersionId()));
        });
        Conv total = converted.get(ScoreAggregates.aggregateSkill(a.examCode()));
        if (total != null && total.scaled() != null) {
            rows.add(new Row(ScoreAggregates.aggregateSkill(a.examCode()), null, null, total.scaled(), total.scoreTableVersionId()));
        }
        return new Scoring(rows, stats);
    }

    private static double round(double v) {
        return BigDecimal.valueOf(v).setScale(2, RoundingMode.HALF_UP).doubleValue();
    }

    /**
     * The conversion of each skill, then the aggregate. Runs with the learner and organisation of the attempt bound, as a request would: each
     * {@code ScoreTables#resolve} is a transaction of the request path of its own (a table that is not there rolls its transaction back, which must not
     * spoil the next skill), and sees the tables of English9x and of that organisation, and the override of that test.
     */
    private Map<String, Conv> convert(Attempt a, String variant, Map<String, Raw> raw) {
        Map<String, Conv> out = new LinkedHashMap<>();
        if (raw.isEmpty()) {
            return out;
        }
        try (CurrentContextHolder.Scope ignored = CurrentContextHolder.bind(a.userId(), a.orgId())) {
            Map<String, Double> scaled = new LinkedHashMap<>();
            raw.forEach((skill, r) -> {
                Conv c = convertSkill(a, scoreSkill(a.examCode(), skill, variant), r.earned());
                out.put(skill, c);
                if (c.scaled() != null) {
                    scaled.put(skill, c.scaled());
                }
            });
            String aggregate = ScoreAggregates.aggregateSkill(a.examCode());
            OptionalDouble total = ScoreAggregates.of(a.examCode(), scaled);
            if (aggregate != null && total.isPresent()) {
                Optional<ResolvedScoreTable> table = resolve(a, aggregate);
                if (table.isPresent() && table.get().function() != ScoreFunction.TABLE) {
                    out.put(aggregate, new Conv(total.getAsDouble(), table.get().scoreTableVersionId()));
                } else {
                    LOG.warn("attempt {}: no aggregate function published for {}; the {} is not written", a.id(), a.examCode(), aggregate);
                }
            }
        } catch (RuntimeException e) {
            if (isOrgDenied(e)) {
                // the member left the organisation since: their organisation's tables are not theirs to read now. Never fall back to another table.
                LOG.warn("attempt {}: the learner is no longer a member of the organisation of the attempt; the scores keep the raw points only", a.id());
                out.clear();
            } else {
                throw e;
            }
        }
        return out;
    }

    /** The name of the score (exams.score_skills) a section skill is converted under; null when it cannot be told (an IELTS Reading test with no variant). */
    static String scoreSkill(String examCode, String skill, String variant) {
        if ("IELTS".equals(examCode) && ScoreAggregates.READING.equals(skill)) {
            return "AC".equals(variant) || "GT".equals(variant) ? "READING_" + variant : null;
        }
        return skill;
    }

    private Conv convertSkill(Attempt a, String scoreSkill, double earned) {
        if (scoreSkill == null) {
            LOG.warn("attempt {}: the test has no variant, so no Reading table can be chosen; the score keeps the raw points only", a.id());
            return Conv.NONE;
        }
        Optional<ResolvedScoreTable> table = resolve(a, scoreSkill);
        if (table.isEmpty()) {
            return Conv.NONE;
        }
        ResolvedScoreTable t = table.get();
        if (t.function() != ScoreFunction.TABLE || t.converter().isEmpty()) {
            LOG.warn("attempt {}: the table for {} is not a raw-to-scaled table; the score keeps the raw points only", a.id(), scoreSkill);
            return Conv.NONE;
        }
        try {
            return new Conv(t.converter().get().convert(earned), t.scoreTableVersionId());
        } catch (IllegalArgumentException e) {
            LOG.warn("attempt {}: the published table for {} does not cover the raw score; the score keeps the raw points only", a.id(), scoreSkill);
            return Conv.NONE;
        }
    }

    private Optional<ResolvedScoreTable> resolve(Attempt a, String scoreSkill) {
        try {
            return Optional.of(primary.execute(status -> tables.resolve(a.examCode(), scoreSkill, Optional.of(a.testVersionId()))));
        } catch (ScoreTableNotFoundException e) {
            LOG.info("attempt {}: no published score table for {} {}; the score keeps the raw points only", a.id(), a.examCode(), scoreSkill);
            return Optional.empty();
        } catch (IllegalArgumentException e) {
            LOG.warn("attempt {}: the exam keeps no score for {} {}: {}", a.id(), a.examCode(), scoreSkill, e.getMessage());
            return Optional.empty();
        }
    }

    private static boolean isOrgDenied(Throwable t) {
        for (Throwable c = t; c != null; c = c.getCause() == c ? null : c.getCause()) {
            if (c instanceof OrgContextDeniedException) {
                return true;
            }
        }
        return false;
    }
}
