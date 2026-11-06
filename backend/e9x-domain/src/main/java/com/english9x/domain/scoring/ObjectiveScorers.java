package com.english9x.domain.scoring;

import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.function.UnaryOperator;

/** Deterministic scorers for auto-scored interaction kinds. */
public final class ObjectiveScorers {

    private ObjectiveScorers() {
    }

    public static ItemScorer forKind(InteractionKind kind) {
        if (kind == null) {
            throw new IllegalArgumentException("kind must not be null");
        }
        return switch (kind) {
            case MCQ_SINGLE, GAP_SELECT, LABEL_DIAGRAM -> single(UnaryOperator.identity());
            case TRUE_FALSE_NG -> single(ObjectiveScorers::canonicalTrueFalse);
            case YES_NO_NG -> single(ObjectiveScorers::canonicalYesNo);
            case MCQ_MULTI -> ObjectiveScorers::scoreMulti;
            case MATCHING -> ObjectiveScorers::scoreMatching;
            case ORDERING -> ObjectiveScorers::scoreOrdering;
            case GAP_TYPED, SHORT_ANSWER -> ObjectiveScorers::scoreTyped;
            case ESSAY, SPEAKING_RESPONSE ->
                    throw new IllegalArgumentException(kind + " is not auto-scored");
        };
    }

    /** Trim, collapse whitespace, lower-case, drop trailing . , ; : ! ? */
    static String normalize(String s) {
        String t = s.trim().replaceAll("\\s+", " ").toLowerCase(Locale.ROOT);
        int end = t.length();
        while (end > 0 && ".,;:!?".indexOf(t.charAt(end - 1)) >= 0) {
            end--;
        }
        return t.substring(0, end).trim();
    }

    private static String canonicalTrueFalse(String n) {
        return switch (n) {
            case "t", "true" -> "true";
            case "f", "false" -> "false";
            case "ng", "not given" -> "not given";
            default -> n;
        };
    }

    private static String canonicalYesNo(String n) {
        return switch (n) {
            case "y", "yes" -> "yes";
            case "n", "no" -> "no";
            case "ng", "not given" -> "not given";
            default -> n;
        };
    }

    private static ItemResult result(AnswerKey key, double fraction) {
        boolean correct = fraction >= 1.0;
        return new ItemResult(key.points() * fraction, key.points(), correct);
    }

    private static boolean isBlank(Answer answer) {
        return answer.values().stream().allMatch(v -> normalize(v).isEmpty());
    }

    private static boolean slotMatches(List<String> variants, String given, UnaryOperator<String> canon) {
        String g = canon.apply(normalize(given));
        if (g.isEmpty()) {
            return false;
        }
        return variants.stream().anyMatch(v -> canon.apply(normalize(v)).equals(g));
    }

    private static ItemScorer single(UnaryOperator<String> canon) {
        return (key, answer) -> {
            if (isBlank(answer) || answer.values().size() != 1) {
                return result(key, 0);
            }
            return result(key, slotMatches(key.slots().get(0), answer.values().get(0), canon) ? 1 : 0);
        };
    }

    private static ItemResult scoreMulti(AnswerKey key, Answer answer) {
        Set<String> correct = new HashSet<>();
        key.slots().get(0).forEach(v -> correct.add(normalize(v)));
        Set<String> chosen = new HashSet<>();
        for (String v : answer.values()) {
            String n = normalize(v);
            if (!n.isEmpty()) {
                chosen.add(n);
            }
        }
        if (chosen.isEmpty() || chosen.size() > correct.size()) {
            return result(key, 0);
        }
        long hits = chosen.stream().filter(correct::contains).count();
        return result(key, (double) hits / correct.size());
    }

    private static ItemResult scoreMatching(AnswerKey key, Answer answer) {
        return result(key, matchedFraction(key, answer, false));
    }

    private static ItemResult scoreTyped(AnswerKey key, Answer answer) {
        return result(key, matchedFraction(key, answer, true));
    }

    private static ItemResult scoreOrdering(AnswerKey key, Answer answer) {
        double f = matchedFraction(key, answer, false);
        return result(key, f >= 1.0 && answer.values().size() == key.slots().size() ? 1 : 0);
    }

    private static double matchedFraction(AnswerKey key, Answer answer, boolean enforceWordLimit) {
        List<List<String>> slots = key.slots();
        int matched = 0;
        for (int i = 0; i < slots.size() && i < answer.values().size(); i++) {
            String given = answer.values().get(i);
            if (enforceWordLimit && key.maxWords() > 0 && wordCount(given) > key.maxWords()) {
                continue;
            }
            if (slotMatches(slots.get(i), given, UnaryOperator.identity())) {
                matched++;
            }
        }
        return (double) matched / slots.size();
    }

    private static int wordCount(String s) {
        String n = normalize(s);
        return n.isEmpty() ? 0 : n.split(" ").length;
    }
}
