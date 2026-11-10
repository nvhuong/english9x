package com.english9x.content.importer;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

class RowParserTest {

    private static final String TYPE = "TOEIC_P5_INCOMPLETE_SENTENCE";
    private final var_types types = null;

    private interface var_types {}

    private final Map<String, com.english9x.catalog.api.QuestionTypeDef> defs = ImportTypes.all();
    private final RowParser parser = new RowParser(ColumnPlanner.plan(defs.get(TYPE)), ImportTypes.validator(defs));

    private static Map<String, String> row(String... kv) {
        Map<String, String> m = new LinkedHashMap<>();
        for (int i = 0; i < kv.length; i += 2) {
            m.put(kv[i], kv[i + 1]);
        }
        return m;
    }

    private Map<String, String> good() {
        return row("stem", "The manager ------- the report.", "options.1.key", "A", "options.1.text", "one", "options.2.key", "B", "options.2.text", "two",
                "options.3.key", "C", "options.3.text", "three", "options.4.key", "D", "options.4.text", "four", "answer.slots.1", "D");
    }

    @Test
    void aGoodRowBecomesAQuestion() {
        RowParser.Result r = parser.parse(5, good());
        assertNotNull(r.parsed(), r.errors().toString());
        assertEquals(4, r.parsed().content().get("options").size());
        assertEquals("D", r.parsed().answerKey().get("slots").get(0).get(0).asText());
        assertNull(r.parsed().explanation());
    }

    @Test
    void reportsRowAndColumn() {
        Map<String, String> noAnswer = good();
        noAnswer.remove("answer.slots.1");
        RowParser.Result r = parser.parse(37, noAnswer);
        assertNull(r.parsed());
        assertEquals(1, r.errors().size(), r.errors().toString());
        RowError e = r.errors().get(0);
        assertEquals(37, e.row());
        assertEquals("answer.slots.1", e.column());
        assertEquals("thiếu đáp án", e.message());
        assertEquals("dòng 37: thiếu đáp án", e.display());
    }

    @Test
    void aMissingContentFieldNamesItsColumn() {
        Map<String, String> m = good();
        m.remove("options.2.text");
        RowError e = parser.parse(9, m).errors().get(0);
        assertEquals(9, e.row());
        assertEquals("options.2.text", e.column());
        assertTrue(e.message().startsWith("thiếu"), e.message());

        Map<String, String> noStem = good();
        noStem.remove("stem");
        assertEquals("stem", parser.parse(10, noStem).errors().get(0).column());
    }

    @Test
    void aGapInANumberedGroupIsReported() {
        Map<String, String> m = good();
        m.remove("options.2.key");
        m.remove("options.2.text");
        List<RowError> errors = parser.parse(4, m).errors();
        assertTrue(errors.stream().anyMatch(e -> e.column().equals("options.2.key") && e.message().contains("ô trống")), errors.toString());
    }

    @Test
    void aWrongTypeNamesItsColumnAndIsReportedForEveryBadCell() {
        Map<String, com.english9x.catalog.api.QuestionTypeDef> d = defs;
        RowParser p = new RowParser(ColumnPlanner.plan(d.get("IELTS_L_MULTIPLE_CHOICE")), ImportTypes.validator(d));
        Map<String, String> m = row("stem", "Q", "options.1.key", "A", "options.1.text", "x", "options.2.key", "B", "options.2.text", "y", "selectcount", "two",
                "points", "-3", "answer.slots.1", "A");
        List<RowError> errors = p.parse(12, m).errors();
        assertTrue(errors.contains(new RowError(12, "selectCount", "phải là số nguyên")), errors.toString());
        assertTrue(errors.contains(new RowError(12, "points", "phải lớn hơn 0, tối đa 1000, tối đa hai chữ số thập phân")), errors.toString());
    }

    @Test
    void variantsOfOneBlankAreSplitOnTheBar() {
        RowParser p = new RowParser(ColumnPlanner.plan(defs.get("IELTS_L_SENTENCE_COMPLETION")), ImportTypes.validator(defs));
        RowParser.Result r = p.parse(3, row("text", "The [[1]] is here", "answer.slots.1", "cat | cats", "answer.maxWords", "2"));
        assertNotNull(r.parsed(), r.errors().toString());
        assertEquals(2, r.parsed().answerKey().get("slots").get(0).size());
    }

    @Test
    void aFileColumnKeepsTheNameAndPutsAStandInId() {
        RowParser p = new RowParser(ColumnPlanner.plan(defs.get("TOEIC_P1_PHOTO")), ImportTypes.validator(defs));
        Map<String, String> m = row("image_media_file", "photo1.png", "audio_media_file", "a.mp3", "options.1.key", "A", "options.1.text", "a", "options.2.key", "B", "options.2.text", "b",
                "options.3.key", "C", "options.3.text", "c", "options.4.key", "D", "options.4.text", "d", "answer.slots.1", "A");
        RowParser.Result r = p.parse(2, m);
        assertNotNull(r.parsed(), r.errors().toString());
        assertEquals(2, r.parsed().media().size());
        RowParser.MediaRef image = r.parsed().media().stream().filter(x -> x.column().equals("image_media_file")).findFirst().orElseThrow();
        assertEquals("photo1.png", image.filename());
        assertEquals("IMAGE", image.wantedKind());
        assertEquals(RowParser.PLACEHOLDER_ID, r.parsed().content().get("imageMediaId").asText());
        java.util.UUID real = java.util.UUID.randomUUID();
        RowParser.substitute(r.parsed(), image, real);
        assertEquals(real.toString(), r.parsed().content().get("imageMediaId").asText());
    }

    // ---- the E2E fixtures (e2e/fixtures/excel) must be what the specs say they are: a header that drifts from the template would fail F11 far from the cause ----

    private List<RowError> errorsOf(String fixture, String typeCode) throws Exception {
        var plan = ColumnPlanner.plan(defs.get(typeCode));
        var sheet = Workbooks.read(java.nio.file.Path.of("../../e2e/fixtures/excel", fixture), 100);
        assertTrue(plan.headers().containsAll(sheet.headers().stream().filter(h -> !h.isEmpty()).toList()), fixture + ": a header that is not in the template of " + typeCode);
        RowParser p = new RowParser(plan, ImportTypes.validator(defs));
        List<RowError> all = new java.util.ArrayList<>();
        for (Workbooks.DataRow r : sheet.rows()) {
            all.addAll(p.parse(r.number(), r.cells()).errors());
        }
        return all;
    }

    @Test
    void theE2eFixturesAreWhatTheSpecsSay() throws Exception {
        assertEquals(List.of(), errorsOf("toeic_p5_ok.xlsx", TYPE));
        assertEquals(List.of(), errorsOf("ielts_r_headings_ok.xlsx", "IELTS_R_MATCHING_HEADINGS"));
        List<RowError> bad = errorsOf("toeic_p5_errors.xlsx", TYPE);
        assertEquals(2, bad.size(), bad.toString());
        assertEquals(new RowError(3, "answer.slots.1", "thiếu đáp án"), bad.get(0));
        assertEquals(5, bad.get(1).row());
        assertEquals("options.2.text", bad.get(1).column());
    }
}
