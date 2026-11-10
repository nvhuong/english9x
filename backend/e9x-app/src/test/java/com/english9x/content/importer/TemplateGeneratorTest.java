package com.english9x.content.importer;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.english9x.catalog.api.QuestionTypeDef;
import com.fasterxml.jackson.databind.JsonNode;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.apache.poi.ss.usermodel.DataFormatter;
import org.apache.poi.ss.usermodel.Row;
import org.apache.poi.ss.usermodel.Sheet;
import org.apache.poi.xssf.usermodel.XSSFWorkbook;
import org.junit.jupiter.api.Test;
import org.springframework.util.unit.DataSize;

class TemplateGeneratorTest {

    private final TemplateGenerator generator = new TemplateGenerator(new ImportProperties(50, DataSize.ofMegabytes(10)));
    private final Map<String, QuestionTypeDef> types = ImportTypes.all();

    /** The names of every scalar leaf property of a schema ({@code stem}, {@code key}, {@code x}, {@code slots}), however deep. */
    private static void leaves(JsonNode schema, String name, Set<String> out) {
        String type = schema.path("type").asText();
        if (type.equals("object")) {
            schema.path("properties").fields().forEachRemaining(e -> leaves(e.getValue(), e.getKey(), out));
        } else if (type.equals("array")) {
            leaves(schema.path("items"), name, out);
        } else if (name != null) {
            out.add(name);
        }
    }

    private XSSFWorkbook open(byte[] bytes) throws IOException {
        return new XSSFWorkbook(new ByteArrayInputStream(bytes));
    }

    @Test
    void thereAreThirtyOneTypes() {
        assertEquals(31, types.size());
    }

    @Test
    void templateColumnsMatchSchema() throws IOException {
        DataFormatter fmt = new DataFormatter();
        for (QuestionTypeDef def : types.values()) {
            try (XSSFWorkbook wb = open(generator.generate(def))) {
                assertEquals("Huong_dan", wb.getSheetName(0), def.code());
                assertEquals("Du_lieu", wb.getSheetName(1), def.code());
                Sheet data = wb.getSheet("Du_lieu");
                List<String> headers = new ArrayList<>();
                Row head = data.getRow(0);
                for (int c = 0; c < head.getLastCellNum(); c++) {
                    headers.add(fmt.formatCellValue(head.getCell(c)));
                }
                assertEquals(ColumnPlan.RESERVED, headers.subList(0, 3), def.code());
                assertEquals(new HashSet<>(headers).size(), headers.size(), def.code() + ": a header twice");
                assertEquals(TemplateGenerator.headersOf(def), headers, def.code());

                // every property of the schemas is a column ...
                Set<String> expected = new HashSet<>();
                leaves(def.contentSchema().getSchemaNode(), null, expected);
                Set<String> answer = new HashSet<>();
                leaves(def.answerKeySchema().getSchemaNode(), null, answer);
                for (String leaf : expected) {
                    String col = leaf.endsWith("MediaId") ? leaf.substring(0, leaf.length() - 7).toLowerCase() + "_media_file" : leaf;
                    assertTrue(headers.stream().anyMatch(h -> !h.startsWith("answer.") && List.of(h.split("\\.")).contains(col)), def.code() + ": no column for content." + leaf);
                }
                for (String leaf : answer) {
                    assertTrue(headers.stream().anyMatch(h -> h.startsWith("answer.") && List.of(h.split("\\.")).contains(leaf)), def.code() + ": no column for answerKey." + leaf);
                }
                // ... and every column is a property (or a numbered element of one), or reserved
                for (String h : headers.subList(3, headers.size())) {
                    String[] parts = h.split("\\.");
                    boolean known = false;
                    for (String part : parts) {
                        known |= expected.contains(part) || answer.contains(part) || expected.contains(part.replace("_media_file", "MediaId")) || part.endsWith("_media_file");
                    }
                    assertTrue(known || h.equals("answer"), def.code() + ": column " + h + " names nothing of the schema");
                }
                // one example row, marked so the importer skips it
                Row example = data.getRow(1);
                assertNotNull(example, def.code());
                assertEquals(ColumnPlan.EXAMPLE_MARK, fmt.formatCellValue(example.getCell(0)), def.code());
                assertNotNull(wb.getSheet("Huong_dan").getRow(1), def.code());
            }
        }
    }

    @Test
    void enumColumnsGetADropDown() throws IOException {
        QuestionTypeDef def = types.get("IELTS_R_IDENTIFYING_INFORMATION");
        ColumnPlan plan = ColumnPlanner.plan(def);
        Column slot = plan.columns().stream().filter(c -> c.header().equals("answer.slots.1")).findFirst().orElseThrow();
        assertFalse(slot.allowed().isEmpty(), "the answer of TRUE/FALSE/NOT GIVEN is an enum");
        try (XSSFWorkbook wb = open(generator.generate(def))) {
            assertFalse(wb.getSheet("Du_lieu").getDataValidations().isEmpty(), "a list of allowed values for the enum column");
        }
    }

    @Test
    void theExampleRowOfEveryTypeIsAValidQuestion() throws IOException {
        DataFormatter fmt = new DataFormatter();
        List<String> failures = new ArrayList<>();
        var validator = ImportTypes.validator(types);
        for (QuestionTypeDef def : types.values()) {
            try (XSSFWorkbook wb = open(generator.generate(def))) {
                Sheet data = wb.getSheet("Du_lieu");
                Map<String, String> cells = new LinkedHashMap<>();
                for (int c = 0; c < data.getRow(0).getLastCellNum(); c++) {
                    String v = fmt.formatCellValue(data.getRow(1).getCell(c));
                    if (!v.isBlank()) {
                        cells.put(fmt.formatCellValue(data.getRow(0).getCell(c)).toLowerCase(), v);
                    }
                }
                RowParser.Result r = new RowParser(ColumnPlanner.plan(def), validator).parse(2, cells);
                if (r.parsed() == null) {
                    failures.add(def.code() + " " + r.errors());
                }
            }
        }
        assertTrue(failures.isEmpty(), "the example rows that do not pass their own schema: " + String.join("\n", failures));
    }
}
