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

    /** Tolerance for floating-point error when flooring a fractional raw score. */
    private static final double FLOOR_EPSILON = 1e-9;

    /** Floors {@code raw} (with a small tolerance) and looks it up; the floored value must be in 0..max. */
    @Override
    public double convert(double raw) {
        if (Double.isNaN(raw) || Double.isInfinite(raw)) {
            throw new IllegalArgumentException("raw score must be finite: " + raw);
        }
        double floored = Math.floor(raw + FLOOR_EPSILON);
        if (floored < 0 || floored > scaled.length - 1) {
            throw new IllegalArgumentException("raw score out of range 0.." + (scaled.length - 1) + ": " + raw);
        }
        return scaled[(int) floored];
    }
}
