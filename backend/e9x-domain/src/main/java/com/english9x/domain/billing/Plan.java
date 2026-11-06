package com.english9x.domain.billing;

public record Plan(String id, long priceVnd, int months, int maxCourses) {
    public Plan {
        if (id == null || id.isBlank()) throw new IllegalArgumentException("id required");
        if (priceVnd <= 0) throw new IllegalArgumentException("priceVnd must be positive");
        if (months < 1) throw new IllegalArgumentException("months must be >= 1");
        if (maxCourses < 1) throw new IllegalArgumentException("maxCourses must be >= 1");
    }
}
