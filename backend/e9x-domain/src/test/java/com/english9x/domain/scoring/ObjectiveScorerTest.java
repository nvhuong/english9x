package com.english9x.domain.scoring;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import org.junit.jupiter.api.Test;

public class ObjectiveScorerTest {

    private static AnswerKey key(double points, int maxWords, String[]... slots) {
        return new AnswerKey(java.util.Arrays.stream(slots).map(List::of).toList(), maxWords, points);
    }

    private static ItemResult score(InteractionKind kind, AnswerKey key, Answer answer) {
        return ObjectiveScorers.forKind(kind).score(key, answer);
    }

    @Test
    public void mcqSingleIgnoresCaseAndWhitespace() {
        ItemResult r = score(InteractionKind.MCQ_SINGLE, key(1, 0, new String[] {"B"}), Answer.of(" b "));
        assertTrue(r.correct());
        assertEquals(1.0, r.earned(), 1e-9);
        assertEquals(1.0, r.max(), 1e-9);
    }

    @Test
    public void tfngAcceptsAbbreviations() {
        assertTrue(score(InteractionKind.TRUE_FALSE_NG, key(1, 0, new String[] {"TRUE"}), Answer.of("T")).correct());
        assertTrue(score(InteractionKind.TRUE_FALSE_NG, key(1, 0, new String[] {"F"}), Answer.of("false")).correct());
        assertFalse(score(InteractionKind.TRUE_FALSE_NG, key(1, 0, new String[] {"TRUE"}), Answer.of("F")).correct());
    }

    @Test
    public void ynngNotGiven() {
        assertTrue(score(InteractionKind.YES_NO_NG, key(1, 0, new String[] {"NOT GIVEN"}), Answer.of("ng")).correct());
        assertTrue(score(InteractionKind.YES_NO_NG, key(1, 0, new String[] {"YES"}), Answer.of("Y")).correct());
        assertTrue(score(InteractionKind.YES_NO_NG, key(1, 0, new String[] {"N"}), Answer.of("no")).correct());
    }

    @Test
    public void mcqMultiPartialCredit() {
        AnswerKey k = key(1, 0, new String[] {"A", "C"});
        ItemResult r = score(InteractionKind.MCQ_MULTI, k, Answer.of("A"));
        assertEquals(0.5, r.earned(), 1e-9);
        assertFalse(r.correct());
        ItemResult full = score(InteractionKind.MCQ_MULTI, k, Answer.of("c", "a"));
        assertEquals(1.0, full.earned(), 1e-9);
        assertTrue(full.correct());
    }

    @Test
    public void mcqMultiTooManyChoicesScoresZero() {
        ItemResult r = score(InteractionKind.MCQ_MULTI, key(1, 0, new String[] {"A", "C"}), Answer.of("A", "B", "C"));
        assertEquals(0.0, r.earned(), 1e-9);
        assertFalse(r.correct());
    }

    @Test
    public void matchingPerSlot() {
        AnswerKey k = key(1, 0, new String[] {"A"}, new String[] {"B"}, new String[] {"C"}, new String[] {"D"});
        ItemResult r = score(InteractionKind.MATCHING, k, Answer.of("a", "b", "c", "x"));
        assertEquals(0.75, r.earned(), 1e-9);
        assertFalse(r.correct());
    }

    @Test
    public void orderingAllOrNothing() {
        AnswerKey k = key(2, 0, new String[] {"1"}, new String[] {"2"}, new String[] {"3"});
        ItemResult ok = score(InteractionKind.ORDERING, k, Answer.of("1", "2", "3"));
        assertEquals(2.0, ok.earned(), 1e-9);
        assertTrue(ok.correct());
        ItemResult bad = score(InteractionKind.ORDERING, k, Answer.of("1", "3", "2"));
        assertEquals(0.0, bad.earned(), 1e-9);
        assertFalse(bad.correct());
    }

    @Test
    public void gapTypedAcceptsVariants() {
        AnswerKey k = new AnswerKey(List.of(List.of("colour", "color")), 0, 1);
        assertTrue(score(InteractionKind.GAP_TYPED, k, Answer.of("Color.")).correct());
        assertTrue(score(InteractionKind.GAP_TYPED, k, Answer.of("  COLOUR ")).correct());
    }

    @Test
    public void gapTypedOverWordLimitIsWrong() {
        AnswerKey k = key(1, 2, new String[] {"old library"});
        assertFalse(score(InteractionKind.GAP_TYPED, k, Answer.of("the old library")).correct());
        assertTrue(score(InteractionKind.GAP_TYPED, k, Answer.of("old library")).correct());
    }

    @Test
    public void blankIsWrong() {
        AnswerKey k = key(1, 0, new String[] {"B"});
        ItemResult r = score(InteractionKind.MCQ_SINGLE, k, Answer.empty());
        assertEquals(0.0, r.earned(), 1e-9);
        assertFalse(r.correct());
        assertFalse(score(InteractionKind.GAP_TYPED, k, Answer.of("   ")).correct());
    }

    @Test
    public void essayIsNotAutoScored() {
        assertFalse(InteractionKind.ESSAY.autoScored());
        assertFalse(InteractionKind.SPEAKING_RESPONSE.autoScored());
        assertTrue(InteractionKind.MCQ_SINGLE.autoScored());
        assertThrows(IllegalArgumentException.class, () -> ObjectiveScorers.forKind(InteractionKind.ESSAY));
        assertThrows(IllegalArgumentException.class, () -> ObjectiveScorers.forKind(InteractionKind.SPEAKING_RESPONSE));
    }

    @Test
    public void rejectsInvalidKeysAndAnswers() {
        assertThrows(IllegalArgumentException.class, () -> new AnswerKey(List.of(), 0, 1));
        assertThrows(IllegalArgumentException.class, () -> new AnswerKey(List.of(List.of("a")), 0, 0));
        assertThrows(IllegalArgumentException.class, () -> new AnswerKey(List.of(List.of("a")), -1, 1));
        assertThrows(IllegalArgumentException.class, () -> new Answer(null));
    }
}
