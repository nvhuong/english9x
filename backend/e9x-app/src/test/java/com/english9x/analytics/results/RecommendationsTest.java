package com.english9x.analytics.results;

import static org.junit.jupiter.api.Assertions.assertEquals;

import com.english9x.analytics.results.ResultsDtos.LessonRef;
import com.english9x.analytics.results.ResultsDtos.Recommendation;
import com.english9x.analytics.results.ResultsDtos.TypeRate;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class RecommendationsTest {

    private static TypeRate rate(String code, int right, int total) {
        return new TypeRate(code, code, right, total, (double) right / total);
    }

    private static List<LessonRef> lessons(String title) {
        return List.of(new LessonRef(UUID.randomUUID(), "course", UUID.randomUUID(), title));
    }

    @Test
    void takesTheThreeLowestRatesThatHaveAnOpenLesson() {
        List<TypeRate> rates = List.of(rate("A", 1, 10), rate("B", 0, 4), rate("C", 5, 10), rate("D", 2, 10), rate("E", 9, 10), rate("F", 0, 8));
        // B has no open lesson (skipped, not replaced by one the person cannot open), A, F, D, C, E do
        Map<String, List<LessonRef>> open = Map.of("A", lessons("a"), "C", lessons("c"), "D", lessons("d"), "E", lessons("e"), "F", lessons("f"));
        List<Recommendation> picked = Recommendations.pick(rates, open, 3);
        assertEquals(List.of("F", "A", "D"), picked.stream().map(Recommendation::typeCode).toList());
    }

    @Test
    void aTypeAlwaysRightIsNotRecommendedAndFewerThanThreeIsFine() {
        List<TypeRate> rates = List.of(rate("A", 10, 10), rate("B", 3, 10));
        List<Recommendation> picked = Recommendations.pick(rates, Map.of("A", lessons("a"), "B", lessons("b")), 3);
        assertEquals(List.of("B"), picked.stream().map(Recommendation::typeCode).toList());
        assertEquals(List.of(), Recommendations.pick(rates, Map.of(), 3));
    }
}
