package com.english9x.content.test.internal;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.english9x.catalog.api.ExamSection;
import com.english9x.content.test.internal.TestStructureValidator.Part;
import com.english9x.content.test.internal.TestStructureValidator.Report;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;

/** The structure of a test against the sections of its exam (spec 3.2, 3.3): a mismatch of counts is a WARNING, a missing skill BLOCKS publication. */
class TestStructureValidatorTest {

    static final List<ExamSection> TOEIC = List.of(
            new ExamSection("P1", "LISTENING", 1, 6), new ExamSection("P2", "LISTENING", 2, 25), new ExamSection("P3", "LISTENING", 3, 39),
            new ExamSection("P4", "LISTENING", 4, 30), new ExamSection("P5", "READING", 5, 30), new ExamSection("P6", "READING", 6, 16),
            new ExamSection("P7", "READING", 7, 54));
    static final List<ExamSection> IELTS = List.of(
            new ExamSection("LISTENING", "LISTENING", 1, 40), new ExamSection("READING", "READING", 2, 40),
            new ExamSection("WRITING", "WRITING", 3, 2), new ExamSection("SPEAKING", "SPEAKING", 4, 3));

    private static List<Part> toeic(int... counts) {
        List<Part> parts = new ArrayList<>();
        for (int i = 0; i < counts.length; i++) {
            parts.add(new Part("P" + (i + 1), counts[i]));
        }
        return parts;
    }

    @Test
    void toeicRequiresSevenParts() {
        // the full test: seven parts, 6 / 25 / 39 / 30 / 30 / 16 / 54 questions = 200
        Report full = TestStructureValidator.validate(TOEIC, toeic(6, 25, 39, 30, 30, 16, 54));
        assertTrue(full.warnings().isEmpty(), full.warnings().toString());
        assertTrue(full.blockers().isEmpty(), full.blockers().toString());
        assertEquals(7, full.sections().size());
        assertEquals(200, full.sections().stream().mapToInt(TestStructureValidator.SectionCheck::actual).sum());

        // part 7 is missing: a warning naming it and the 54 it needs; every skill is still there, so publication is not blocked by it
        Report sixParts = TestStructureValidator.validate(TOEIC, toeic(6, 25, 39, 30, 30, 16));
        assertEquals(1, sixParts.warnings().size(), sixParts.warnings().toString());
        assertEquals("P7", sixParts.warnings().get(0).sectionCode());
        assertTrue(sixParts.warnings().get(0).message().contains("54"), sixParts.warnings().get(0).message());
        assertTrue(sixParts.blockers().isEmpty());
        assertEquals(7, sixParts.sections().size(), "the report always has one line per section of the exam");
        assertEquals("MISSING", sixParts.sections().get(6).state());

        // a part with the wrong number of questions: a warning with both numbers
        Report off = TestStructureValidator.validate(TOEIC, toeic(6, 25, 39, 30, 29, 16, 55));
        assertEquals(List.of("P5", "P7"), off.warnings().stream().map(TestStructureValidator.Issue::sectionCode).toList());
        assertEquals("SHORT", off.sections().get(4).state());
        assertEquals("OVER", off.sections().get(6).state());
        assertTrue(off.warnings().get(0).message().contains("30") && off.warnings().get(0).message().contains("29"), off.warnings().get(0).message());
        assertTrue(off.blockers().isEmpty());

        // the whole Reading section missing: the skill is missing, which blocks
        Report listeningOnly = TestStructureValidator.validate(TOEIC, toeic(6, 25, 39, 30));
        assertEquals(List.of("READING"), listeningOnly.blockers().stream().map(TestStructureValidator.Issue::skill).distinct().toList());
        assertEquals(3, listeningOnly.warnings().size(), "P5, P6 and P7 are each missing");

        // a part with no questions counts as missing
        assertEquals("MISSING", TestStructureValidator.validate(TOEIC, toeic(6, 25, 39, 30, 30, 16, 0)).sections().get(6).state());
    }

    @Test
    void ieltsRequiresFourSkills() {
        Report full = TestStructureValidator.validate(IELTS, List.of(new Part("LISTENING", 40), new Part("READING", 40), new Part("WRITING", 2), new Part("SPEAKING", 3)));
        assertTrue(full.warnings().isEmpty() && full.blockers().isEmpty(), full.toString());

        // Writing and Speaking missing: both block, and both are also warnings of count
        Report two = TestStructureValidator.validate(IELTS, List.of(new Part("LISTENING", 40), new Part("READING", 40)));
        assertEquals(List.of("WRITING", "SPEAKING"), two.blockers().stream().map(TestStructureValidator.Issue::skill).toList());
        assertEquals(2, two.warnings().size());

        // 39 listening questions is a warning, not a block: the skill is there
        Report short39 = TestStructureValidator.validate(IELTS, List.of(new Part("LISTENING", 39), new Part("READING", 40), new Part("WRITING", 2), new Part("SPEAKING", 3)));
        assertEquals(1, short39.warnings().size());
        assertTrue(short39.blockers().isEmpty());

        // nothing at all: every skill is missing
        assertEquals(4, TestStructureValidator.validate(IELTS, List.of()).blockers().size());
    }
}
