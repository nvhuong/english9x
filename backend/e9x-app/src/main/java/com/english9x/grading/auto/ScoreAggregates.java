package com.english9x.grading.auto;

import com.english9x.domain.scoring.Ielts;
import com.english9x.domain.scoring.Toeic;
import java.util.Map;
import java.util.OptionalDouble;

/**
 * The scores that combine the skills of an attempt (spec 10.2, the aggregate functions {@code SUM} and {@code IELTS_OVERALL}): the rules are the domain's
 * ({@link Toeic#total}, {@link Ielts#overall}); this only says WHEN there is one. Pure, so that the Writing/Speaking tasks (P4.2, P4.3), which finish the
 * last skills of an IELTS attempt, call the same code. A value the domain refuses (a TOEIC section that is not 5..495 in steps of 5, an IELTS band that is
 * not a half band) gives no aggregate: it is a wrong table, and an aggregate is never invented around it.
 */
public final class ScoreAggregates {

    /** The skills of a TOEIC Listening and Reading attempt, as {@code attempt_sections.skill}. */
    public static final String LISTENING = "LISTENING";
    public static final String READING = "READING";
    public static final String WRITING = "WRITING";
    public static final String SPEAKING = "SPEAKING";
    /** The aggregate skills, as {@code exams.score_skills} names them. */
    public static final String TOEIC_TOTAL = "TOTAL";
    public static final String IELTS_OVERALL = "OVERALL";

    private ScoreAggregates() {}

    /** The aggregate this exam keeps, or null when it keeps none. */
    public static String aggregateSkill(String examCode) {
        return switch (examCode) {
            case "TOEIC" -> TOEIC_TOTAL;
            case "IELTS" -> IELTS_OVERALL;
            default -> null;
        };
    }

    /** TOEIC total from the two section scores; empty while one is missing or the domain refuses the pair. */
    public static OptionalDouble toeicTotal(Map<String, Double> scaled) {
        Double l = scaled.get(LISTENING);
        Double r = scaled.get(READING);
        if (l == null || r == null || l != Math.rint(l) || r != Math.rint(r)) {
            return OptionalDouble.empty();
        }
        try {
            return OptionalDouble.of(Toeic.total((int) (double) l, (int) (double) r));
        } catch (IllegalArgumentException e) {
            return OptionalDouble.empty();
        }
    }

    /** IELTS overall band from the four skill bands; empty unless all four are there and are bands. */
    public static OptionalDouble ieltsOverall(Map<String, Double> bands) {
        Double l = bands.get(LISTENING);
        Double r = bands.get(READING);
        Double w = bands.get(WRITING);
        Double s = bands.get(SPEAKING);
        if (l == null || r == null || w == null || s == null) {
            return OptionalDouble.empty();
        }
        try {
            return OptionalDouble.of(Ielts.overall(l, r, w, s));
        } catch (IllegalArgumentException e) {
            return OptionalDouble.empty();
        }
    }

    /** The aggregate of the exam from the skill scores so far. */
    public static OptionalDouble of(String examCode, Map<String, Double> scaled) {
        return switch (examCode) {
            case "TOEIC" -> toeicTotal(scaled);
            case "IELTS" -> ieltsOverall(scaled);
            default -> OptionalDouble.empty();
        };
    }
}
