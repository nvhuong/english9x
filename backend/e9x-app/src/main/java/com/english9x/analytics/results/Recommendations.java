package com.english9x.analytics.results;

import com.english9x.analytics.results.ResultsDtos.LessonRef;
import com.english9x.analytics.results.ResultsDtos.Recommendation;
import com.english9x.analytics.results.ResultsDtos.TypeRate;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;

/**
 * The rule of a recommendation: of the question types the person gets wrong, the {@code limit} with the LOWEST rate of right answers THAT HAVE a method lesson the person may
 * open (a type with no lesson in an entitled course is skipped, never replaced by a lesson they cannot open). A type answered right every time has nothing to improve and is
 * not recommended. Ties: the type with more questions first (the firmer evidence), then the code.
 */
final class Recommendations {

    private Recommendations() {}

    static List<Recommendation> pick(List<TypeRate> rates, Map<String, List<LessonRef>> openLessons, int limit) {
        List<TypeRate> weakest = new ArrayList<>(rates);
        weakest.sort(Comparator.comparingDouble(TypeRate::rate).thenComparing(Comparator.comparingInt(TypeRate::total).reversed()).thenComparing(TypeRate::typeCode));
        List<Recommendation> out = new ArrayList<>();
        for (TypeRate t : weakest) {
            if (out.size() >= limit) {
                break;
            }
            List<LessonRef> lessons = openLessons.getOrDefault(t.typeCode(), List.of());
            if (t.rate() < 1.0 && !lessons.isEmpty()) {
                out.add(new Recommendation(t.typeCode(), t.typeName(), t.rate(), lessons));
            }
        }
        return out;
    }
}
