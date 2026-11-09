package com.english9x.catalog.internal;

import com.english9x.domain.grading.GradingMode;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

/**
 * {@code e9x.grading.default-mode}: who grades writing and speaking for an exam that has no exam-level policy row. A self-set assumption
 * (spec 15.1), not a decision of the project owner; the administrator's policy row for the exam wins over it.
 */
@ConfigurationProperties("e9x.grading")
record CatalogProperties(@DefaultValue("AI") GradingMode defaultMode) {}
