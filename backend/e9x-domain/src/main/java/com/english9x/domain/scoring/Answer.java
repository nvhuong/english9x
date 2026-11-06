package com.english9x.domain.scoring;

import java.util.List;

/** A learner's response: one value per slot (or the chosen options for multi-select). */
public record Answer(List<String> values) {

    public Answer {
        if (values == null) {
            throw new IllegalArgumentException("values must not be null");
        }
        for (String v : values) {
            if (v == null) {
                throw new IllegalArgumentException("values must not contain null");
            }
        }
        values = List.copyOf(values);
    }

    public static Answer of(String... v) {
        return new Answer(List.of(v));
    }

    public static Answer empty() {
        return new Answer(List.of());
    }
}
