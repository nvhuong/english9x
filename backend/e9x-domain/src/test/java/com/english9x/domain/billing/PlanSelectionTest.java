package com.english9x.domain.billing;

import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

import java.util.List;

public class PlanSelectionTest {
    private static final Plan PLAN = new Plan("p2", 300000, 6, 2);

    @Test
    public void acceptsUpToMax() {
        PlanSelection.validate(PLAN, List.of("a"));
        PlanSelection.validate(PLAN, List.of("a", "b"));
    }

    @Test
    public void rejectsOverMax() {
        assertThrows(IllegalArgumentException.class, () -> PlanSelection.validate(PLAN, List.of("a", "b", "c")));
    }

    @Test
    public void rejectsDuplicates() {
        assertThrows(IllegalArgumentException.class, () -> PlanSelection.validate(PLAN, List.of("a", "a")));
    }

    @Test
    public void rejectsEmpty() {
        assertThrows(IllegalArgumentException.class, () -> PlanSelection.validate(PLAN, List.of()));
    }
}
