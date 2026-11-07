package com.english9x.catalog.api;

import java.util.List;

/**
 * An exam of the catalog. {@code scoreSkills} are the keys scores are converted under ({@code READING_AC} and {@code READING_GT} are two
 * keys because the two conversions are two tables); {@code variants} the variants a test can have (IELTS: AC, GT).
 */
public record ExamInfo(String code, String name, List<String> variants, List<String> scoreSkills, List<ExamSection> sections) {}
