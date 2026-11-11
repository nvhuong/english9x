package com.english9x.grading.api;

import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Which stored score of a skill is the one shown (spec 10.3, decision 7: "the AI score shows at once as preliminary; the teacher's replaces it and is official").
 * One rule for everybody who needs a score of an attempt (the announcement, the result page of P4.4): an OFFICIAL row beats a PRELIMINARY one, and among rows of the
 * same kind the teacher's beats the AI's, which beats the automatic one. It is the table form of {@code domain.grading.OfficialScore.select}: a teacher's score is
 * OFFICIAL in every mode and always replaces the AI's.
 */
public final class DisplayedScores {

    /** One stored score row: its skill, kind (PRELIMINARY / OFFICIAL), source (AUTO / AI / TEACHER) and the converted value. */
    public record Candidate(String skill, String kind, String source, double scaled) {
        public boolean isOfficial() {
            return "OFFICIAL".equals(kind);
        }
    }

    private static final Comparator<Candidate> BEST = Comparator
            .comparingInt((Candidate c) -> c.isOfficial() ? 0 : 1)
            .thenComparingInt(c -> switch (c.source()) {
                case "TEACHER" -> 0;
                case "AI" -> 1;
                default -> 2;
            });

    private DisplayedScores() {}

    /** The row shown for each skill that has any. */
    public static Map<String, Candidate> select(List<Candidate> rows) {
        Map<String, Candidate> out = new LinkedHashMap<>();
        for (Candidate c : rows) {
            out.merge(c.skill(), c, (a, b) -> BEST.compare(a, b) <= 0 ? a : b);
        }
        return out;
    }
}
