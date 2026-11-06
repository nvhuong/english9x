package com.english9x.domain.scoring;

import java.util.Map;
import java.util.TreeMap;

/** Converts a raw score to a scaled score through a lookup table (raw 0..max). */
public final class TableConverter implements ScoreConverter {

    private final double[] scaled;

    private TableConverter(double[] scaled) {
        this.scaled = scaled;
    }

    public static TableConverter of(Map<Integer, Double> rawToScaled) {
        if (rawToScaled == null || rawToScaled.isEmpty()) {
            throw new IllegalArgumentException("table must not be empty");
        }
        TreeMap<Integer, Double> sorted = new TreeMap<>(rawToScaled);
        double[] values = new double[sorted.size()];
        int expected = 0;
        for (Map.Entry<Integer, Double> e : sorted.entrySet()) {
            if (e.getKey() != expected) {
                throw new IllegalArgumentException("keys must be contiguous from 0, missing " + expected);
            }
            double v = e.getValue();
            if (Double.isNaN(v) || (expected > 0 && v < values[expected - 1])) {
                throw new IllegalArgumentException("values must be non-decreasing");
            }
            values[expected++] = v;
        }
        return new TableConverter(values);
    }

    @Override
    public double convert(double raw) {
        if (raw != Math.rint(raw) || raw < 0 || raw > scaled.length - 1) {
            throw new IllegalArgumentException("raw score out of range 0.." + (scaled.length - 1) + ": " + raw);
        }
        return scaled[(int) raw];
    }
}
