package com.english9x.domain.scoring;

import java.util.List;

/**
 * Correct answer for an item. Each slot lists the accepted variants.
 * {@code maxWords == 0} means no word limit.
 */
public record AnswerKey(List<List<String>> slots, int maxWords, double points) {

    public AnswerKey {
        if (slots == null || slots.isEmpty()) {
            throw new IllegalArgumentException("slots must not be empty");
        }
        if (maxWords < 0) {
            throw new IllegalArgumentException("maxWords must not be negative");
        }
        if (!(points > 0) || Double.isInfinite(points)) {
            throw new IllegalArgumentException("points must be positive");
        }
        slots = slots.stream().map(slot -> {
            if (slot == null || slot.isEmpty()) {
                throw new IllegalArgumentException("each slot needs at least one accepted variant");
            }
            return List.copyOf(slot);
        }).toList();
    }
}
