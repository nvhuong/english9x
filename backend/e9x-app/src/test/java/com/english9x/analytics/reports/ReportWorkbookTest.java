package com.english9x.analytics.reports;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

class ReportWorkbookTest {

    @Test
    void sheetNameFollowsExcelRules() {
        assertEquals("Lớp 10A 1", ReportWorkbook.sheetName("Lớp 10A/1"));
        assertEquals("Class", ReportWorkbook.sheetName("   "));
        assertEquals("Class", ReportWorkbook.sheetName(null));
        assertEquals(31, ReportWorkbook.sheetName("x".repeat(80)).length());
        assertTrue(ReportWorkbook.sheetName("a[b]c:d*e?f\\g").matches("[^\\[\\]:*?/\\\\]+"));
    }

    @Test
    void everyColumnHasAWordInBothLanguages() {
        for (String lang : new String[] {"vi", "en"}) {
            ReportWorkbook.Labels labels = ReportWorkbook.Labels.of(lang);
            for (String c : ReportWorkbook.COLUMNS) {
                assertTrue(labels.text().containsKey(c), lang + " lacks " + c);
            }
            assertTrue(labels.text().containsKey("PRELIMINARY") && labels.text().containsKey("OFFICIAL"));
        }
        assertEquals("vi", ReportWorkbook.Labels.of("fr").lang());
    }
}
