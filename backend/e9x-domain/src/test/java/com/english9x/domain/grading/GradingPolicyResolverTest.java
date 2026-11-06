package com.english9x.domain.grading;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.util.Optional;
import org.junit.jupiter.api.Test;

public class GradingPolicyResolverTest {

    @Test
    public void testOverridesOrgAndExam() {
        assertEquals(GradingMode.AI,
                GradingPolicyResolver.resolve(Optional.of(GradingMode.AI), Optional.of(GradingMode.TEACHER), GradingMode.BOTH));
    }

    @Test
    public void orgOverridesExam() {
        assertEquals(GradingMode.TEACHER,
                GradingPolicyResolver.resolve(Optional.empty(), Optional.of(GradingMode.TEACHER), GradingMode.AI));
    }

    @Test
    public void fallsBackToExam() {
        assertEquals(GradingMode.BOTH,
                GradingPolicyResolver.resolve(Optional.empty(), Optional.empty(), GradingMode.BOTH));
    }
}
