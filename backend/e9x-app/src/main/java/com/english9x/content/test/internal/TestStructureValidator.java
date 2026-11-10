package com.english9x.content.test.internal;

import com.english9x.catalog.api.ExamSection;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

/**
 * Checks the structure of a test against the sections of its exam (spec 3.2, 3.3: TOEIC seven parts of 6 / 25 / 39 / 30 / 30 / 16 / 54 questions; IELTS
 * Listening 40, Reading 40, Writing 2 tasks, Speaking 3 parts). Pure: the caller counts the questions of each part.
 *
 * <p>Two grades, as the plan says: a section with the wrong number of questions, or none, is a WARNING (an author may be half way, or build a short mock
 * test on purpose); a SKILL with no question in any of its sections is a BLOCKER: the test is refused for review (a result cannot be scored without it).
 */
final class TestStructureValidator {

    /** One part of the test as built: the section it is for and how many questions its groups hold together. */
    record Part(String sectionCode, int questions) {}

    /** One line per section of the exam. {@code state}: OK, MISSING (no questions), SHORT or OVER. */
    record SectionCheck(String sectionCode, String skill, int expected, int actual, String state) {}

    record Issue(String sectionCode, String skill, String code, String message) {}

    record Report(List<SectionCheck> sections, List<Issue> warnings, List<Issue> blockers) {}

    private TestStructureValidator() {}

    static Report validate(List<ExamSection> sections, List<Part> parts) {
        List<SectionCheck> checks = new ArrayList<>();
        List<Issue> warnings = new ArrayList<>();
        Set<String> skills = new LinkedHashSet<>();
        Set<String> skillsWithQuestions = new LinkedHashSet<>();
        for (ExamSection s : sections) {
            skills.add(s.skill());
            int actual = parts.stream().filter(p -> p.sectionCode().equals(s.code())).mapToInt(Part::questions).sum();
            String state = actual == 0 ? "MISSING" : actual < s.itemCount() ? "SHORT" : actual > s.itemCount() ? "OVER" : "OK";
            checks.add(new SectionCheck(s.code(), s.skill(), s.itemCount(), actual, state));
            if (actual > 0) {
                skillsWithQuestions.add(s.skill());
            }
            switch (state) {
                case "MISSING" -> warnings.add(new Issue(s.code(), s.skill(), "section-missing", s.code() + " has no questions; it needs " + s.itemCount()));
                case "SHORT", "OVER" -> warnings.add(new Issue(s.code(), s.skill(), "section-count",
                        s.code() + " has " + actual + " questions; it needs " + s.itemCount()));
                default -> { }
            }
        }
        List<Issue> blockers = new ArrayList<>();
        for (String skill : skills) {
            if (!skillsWithQuestions.contains(skill)) {
                blockers.add(new Issue(null, skill, "skill-missing", "the test has no " + skill + " questions"));
            }
        }
        return new Report(List.copyOf(checks), List.copyOf(warnings), List.copyOf(blockers));
    }
}
