package com.english9x.domain.grading;

import java.util.Optional;

public final class GradingPolicyResolver {

    private GradingPolicyResolver() {
    }

    /** Test-level setting wins over organization, which wins over the exam default. */
    public static GradingMode resolve(Optional<GradingMode> test, Optional<GradingMode> org, GradingMode exam) {
        if (test == null || org == null || exam == null) {
            throw new IllegalArgumentException("arguments must not be null");
        }
        return test.orElseGet(() -> org.orElse(exam));
    }
}
