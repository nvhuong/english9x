package com.english9x.domain.billing;

import java.util.HashSet;
import java.util.List;

public final class PlanSelection {
    private PlanSelection() {}

    public static void validate(Plan plan, List<String> courseIds) {
        if (courseIds == null || courseIds.isEmpty()) {
            throw new IllegalArgumentException("select at least one course");
        }
        if (new HashSet<>(courseIds).size() != courseIds.size()) {
            throw new IllegalArgumentException("duplicate courses");
        }
        if (courseIds.size() > plan.maxCourses()) {
            throw new IllegalArgumentException("plan allows at most " + plan.maxCourses() + " courses");
        }
    }
}
