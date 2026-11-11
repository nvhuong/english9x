package com.english9x.grading.ai;

import com.fasterxml.jackson.databind.JsonNode;
import java.math.BigDecimal;
import java.math.RoundingMode;

/** The band of an answer: the mean of its criterion scores, to the nearest half (IELTS rule: .25 goes up to .5, .75 to the next whole band). */
public final class Bands {

    private Bands() {}

    public static double of(JsonNode criteria, JsonNode answer) {
        double sum = 0;
        int n = 0;
        if (criteria != null && criteria.isArray()) {
            for (JsonNode c : criteria) {
                JsonNode v = answer.path(c.path("code").asText());
                if (v.isNumber()) {
                    sum += v.asDouble();
                    n++;
                }
            }
        }
        return n == 0 ? 0 : nearestHalf(sum / n);
    }

    public static double nearestHalf(double v) {
        return BigDecimal.valueOf(v * 2).setScale(0, RoundingMode.HALF_UP).doubleValue() / 2;
    }
}
