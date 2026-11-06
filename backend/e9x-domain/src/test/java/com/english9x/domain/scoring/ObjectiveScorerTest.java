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

    @Test
    public void singleSlotKindsRequireExactlyOneKeySlot() {
        AnswerKey two = key(1, 0, new String[] {"A"}, new String[] {"B"});
        for (InteractionKind k : List.of(InteractionKind.MCQ_SINGLE, InteractionKind.GAP_SELECT,
                InteractionKind.LABEL_DIAGRAM, InteractionKind.TRUE_FALSE_NG, InteractionKind.YES_NO_NG,
                InteractionKind.MCQ_MULTI)) {
            assertThrows(IllegalArgumentException.class, () -> score(k, two, Answer.of("A")));
            assertThrows(IllegalArgumentException.class, () -> score(k, two, Answer.empty()));
        }
    }

    @Test
    public void typedMatchesAcrossNonBreakingSpace() {
        AnswerKey k = key(1, 2, new String[] {"old library"});
        assertTrue(score(InteractionKind.GAP_TYPED, k, Answer.of("old\u00A0library")).correct());
        AnswerKey nbspKey = key(1, 2, new String[] {"old\u00A0library"});
        assertTrue(score(InteractionKind.GAP_TYPED, nbspKey, Answer.of("old library")).correct());
    }

    @Test
    public void typedMatchesCurlyQuotes() {
        AnswerKey k = key(1, 0, new String[] {"children's"});
        assertTrue(score(InteractionKind.GAP_TYPED, k, Answer.of("children\u2019s")).correct());
        assertTrue(score(InteractionKind.GAP_TYPED, key(1, 0, new String[] {"children\u2018s"}), Answer.of("children's")).correct());
        assertTrue(score(InteractionKind.SHORT_ANSWER, key(1, 0, new String[] {"\"safe\""}), Answer.of("\u201Csafe\u201D")).correct());
    }

    @Test
    public void typedCollapsesInnerRepeatedSpaces() {
        AnswerKey k = key(1, 2, new String[] {"old library"});
        assertTrue(score(InteractionKind.GAP_TYPED, k, Answer.of("old    library")).correct());
        assertTrue(score(InteractionKind.GAP_TYPED, k, Answer.of("old \u2003\t library")).correct());
    }

    @Test
    public void typedAppliesCompatibilityNormalization() {
        // NFKC folds full-width letters to ASCII
        assertTrue(score(InteractionKind.GAP_TYPED, key(1, 0, new String[] {"abc"}), Answer.of("\uFF21\uFF22\uFF23")).correct());
    }

    @Test
    public void shortAnswerScoresDirectly() {
        AnswerKey k = key(2, 3, new String[] {"in the morning", "morning"});
        ItemResult ok = score(InteractionKind.SHORT_ANSWER, k, Answer.of("In the  Morning."));
        assertTrue(ok.correct());
        assertEquals(2.0, ok.earned(), 1e-9);
        assertEquals(2.0, ok.max(), 1e-9);
        ItemResult wrong = score(InteractionKind.SHORT_ANSWER, k, Answer.of("evening"));
        assertFalse(wrong.correct());
        assertEquals(0.0, wrong.earned(), 1e-9);
        assertFalse(score(InteractionKind.SHORT_ANSWER, k, Answer.of("early in the morning")).correct(), "word limit");
    }

    @Test
    public void gapTypedMultiSlotPartialCredit() {
        AnswerKey k = key(3, 0, new String[] {"river"}, new String[] {"bridge"}, new String[] {"tower"});
        ItemResult r = score(InteractionKind.GAP_TYPED, k, Answer.of("River", "brigde", "tower"));
        assertEquals(2.0, r.earned(), 1e-9);
        assertEquals(3.0, r.max(), 1e-9);
        assertFalse(r.correct());
    }

    @Test
    public void answerKeyRejectsNullOrBlankVariants() {
        java.util.List<String> withNull = new java.util.ArrayList<>();
        withNull.add("a");
        withNull.add(null);
        assertThrows(IllegalArgumentException.class, () -> new AnswerKey(List.of(withNull), 0, 1));
        assertThrows(IllegalArgumentException.class, () -> new AnswerKey(List.of(List.of("a", "")), 0, 1));
        assertThrows(IllegalArgumentException.class, () -> new AnswerKey(List.of(List.of("   ")), 0, 1));
        assertThrows(IllegalArgumentException.class, () -> new AnswerKey(List.of(List.of("\u00A0")), 0, 1));
        assertThrows(IllegalArgumentException.class, () -> new AnswerKey(List.of(List.of("a"), List.of()), 0, 1));
        java.util.List<List<String>> nullSlot = new java.util.ArrayList<>();
        nullSlot.add(List.of("a"));
        nullSlot.add(null);
        assertThrows(IllegalArgumentException.class, () -> new AnswerKey(nullSlot, 0, 1));
    }
}
