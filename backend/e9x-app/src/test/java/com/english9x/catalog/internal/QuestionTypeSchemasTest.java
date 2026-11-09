package com.english9x.catalog.internal;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.english9x.catalog.api.FieldError;
import com.english9x.catalog.api.QuestionTypeDef;
import com.english9x.catalog.api.UnknownQuestionTypeException;
import com.english9x.domain.scoring.InteractionKind;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.junit.jupiter.api.Test;

/**
 * The 31 seeded question types and their schemas, without a database: the seed migration is read as text, so the test and the
 * production start-up check agree on what "seeded" means. Each type has one valid and one invalid example under
 * src/test/resources/catalog/examples.
 */
class QuestionTypeSchemasTest {

    static final ObjectMapper JSON = new ObjectMapper();
    static final Pattern SEED_ROW = Pattern.compile(
            "\\(\\s*'([A-Z0-9_]+)',\\s*'(TOEIC|IELTS)',\\s*'([A-Z0-9_]+)',\\s*'([A-Z_]+)',\\s*'(?:[^']|'')*',\\s*'([A-Z0-9_]+)'\\)", Pattern.DOTALL);

    /** The rows of the seed migration, in file order. */
    static List<QuestionTypeCatalog.TypeRow> seededRows() throws IOException {
        String sql = Files.readString(Path.of("src/main/resources/db/migration/V202611100002__catalog_seed_ielts_toeic.sql"), StandardCharsets.UTF_8);
        String block = sql.substring(sql.indexOf("INSERT INTO question_types"), sql.indexOf("-- ---- aggregate score functions"));
        List<QuestionTypeCatalog.TypeRow> rows = new ArrayList<>();
        Matcher m = SEED_ROW.matcher(block);
        while (m.find()) {
            rows.add(new QuestionTypeCatalog.TypeRow(m.group(1), m.group(2), m.group(3), m.group(4), Optional.of(m.group(5))));
        }
        return rows;
    }

    static QuestionTypeCatalog catalog() throws IOException {
        return QuestionTypeCatalog.build(seededRows(), new SchemaFiles("classpath*:catalog/question-types/*.schema.json").load());
    }

    static JsonNode example(String code, String kind) throws IOException {
        return JSON.readTree(Path.of("src/test/resources/catalog/examples/" + code + "." + kind + ".json").toFile());
    }

    @Test
    void everySeededTypeHasValidSchemas() throws IOException {
        List<QuestionTypeCatalog.TypeRow> rows = seededRows();
        assertEquals(31, rows.size(), "the spec lists 8 TOEIC + 6 IELTS Listening + 11 IELTS Reading + 3 Writing + 3 Speaking types");
        assertEquals(8, rows.stream().filter(r -> r.examCode().equals("TOEIC")).count());
        QuestionTypeCatalog catalog = catalog();
        SchemaValidatorImpl validator = new SchemaValidatorImpl(catalog);
        assertEquals(31, catalog.all().size());
        for (QuestionTypeCatalog.TypeRow r : rows) {
            QuestionTypeDef def = catalog.get(r.code());
            assertEquals(InteractionKind.valueOf(r.kind()), def.kind(), r.code());
            assertEquals(r.examCode(), def.examCode(), r.code());
            assertEquals(r.section(), def.section(), r.code());
            assertTrue(def.excelTemplateId().isPresent(), r.code());

            JsonNode valid = example(r.code(), "valid");
            List<FieldError> none = validator.validate(r.code(), valid.get("content"), valid.get("answerKey"));
            assertTrue(none.isEmpty(), r.code() + " valid example refused: " + none);

            JsonNode invalid = example(r.code(), "invalid");
            List<FieldError> errors = validator.validate(r.code(), invalid.get("content"), invalid.get("answerKey"));
            assertFalse(errors.isEmpty(), r.code() + " invalid example accepted");
            String expected = invalid.get("expectedErrorPath").asText();
            assertTrue(errors.stream().anyMatch(e -> e.path().equals(expected)),
                    r.code() + ": expected an error at " + expected + " but got " + errors);
        }
    }

    @Test
    void thereIsNoSchemaFileWithoutAType() throws IOException {
        Map<String, SchemaFiles.SchemaFile> files = new SchemaFiles("classpath*:catalog/question-types/*.schema.json").load();
        assertEquals(31, files.size());
        assertEquals(seededRows().stream().map(QuestionTypeCatalog.TypeRow::code).sorted().toList(), files.keySet().stream().sorted().toList());
    }

    @Test
    void anAnswerKeyErrorNeverRepeatsTheAnswer() throws IOException {
        SchemaValidatorImpl validator = new SchemaValidatorImpl(catalog());
        for (QuestionTypeCatalog.TypeRow r : seededRows()) {
            // every string of the answer key becomes a sentinel, in a place the schema does not allow it
            JsonNode valid = example(r.code(), "valid");
            JsonNode key = withSentinels(valid.get("answerKey"));
            for (FieldError e : validator.validate(r.code(), valid.get("content"), key)) {
                assertFalse(e.message().contains("SENTINEL-SECRET") || e.path().contains("SENTINEL-SECRET"), r.code() + ": " + e);
            }
            // and the same for answers the schema refuses outright
            ObjectNode wrongShape = JSON.createObjectNode();
            wrongShape.put("slots", "SENTINEL-SECRET");
            wrongShape.putArray("extra").add("SENTINEL-SECRET");
            wrongShape.put("SENTINEL-SECRET-PROPERTY", 1);
            for (FieldError e : validator.validate(r.code(), valid.get("content"), wrongShape)) {
                assertFalse(e.message().contains("SENTINEL-SECRET"), r.code() + ": " + e);
            }
        }
    }

    private static JsonNode withSentinels(JsonNode n) {
        if (n.isTextual()) {
            return JSON.getNodeFactory().textNode("SENTINEL-SECRET");
        }
        if (n.isArray()) {
            ArrayNode a = JSON.createArrayNode();
            n.forEach(c -> a.add(withSentinels(c)));
            return a;
        }
        if (n.isObject()) {
            ObjectNode o = JSON.createObjectNode();
            for (Iterator<Map.Entry<String, JsonNode>> it = n.fields(); it.hasNext();) {
                Map.Entry<String, JsonNode> f = it.next();
                o.set(f.getKey(), withSentinels(f.getValue()));
            }
            return o;
        }
        return n;
    }

    @Test
    void missingOrNullContentAndAnswerKeyAreErrorsNotExceptions() throws IOException {
        SchemaValidatorImpl validator = new SchemaValidatorImpl(catalog());
        List<FieldError> errors = validator.validate("TOEIC_P5_INCOMPLETE_SENTENCE", null, JSON.nullNode());
        assertEquals(List.of("answerKey", "content"), errors.stream().map(FieldError::path).toList());
    }

    @Test
    void unknownTypeIsAnException() throws IOException {
        SchemaValidatorImpl validator = new SchemaValidatorImpl(catalog());
        UnknownQuestionTypeException e = assertThrows(UnknownQuestionTypeException.class,
                () -> validator.validate("TOEIC_P99", JSON.createObjectNode(), JSON.createObjectNode()));
        assertEquals("TOEIC_P99", e.code());
        assertEquals(Optional.empty(), catalog().find("TOEIC_P99"));
    }

    @Test
    void errorsCarryTheJsonPathOfEveryProblem() throws IOException {
        SchemaValidatorImpl validator = new SchemaValidatorImpl(catalog());
        JsonNode content = JSON.readTree("{\"stem\":\"x\",\"options\":[{\"key\":\"A\",\"text\":\"a\"},{\"key\":\"B\"},{\"key\":\"C\",\"text\":\"c\",\"extra\":1},{\"key\":\"D\",\"text\":\"d\"}],\"bogus\":true}");
        List<String> paths = validator.validate("TOEIC_P5_INCOMPLETE_SENTENCE", content, JSON.readTree("{\"slots\":[[\"A\"]]}")).stream().map(FieldError::path).sorted().toList();
        assertEquals(List.of("content.bogus", "content.options[1].text", "content.options[2].extra"), paths);
    }

    // ---- what a JSON Schema cannot say: the checks that keep a question scorable ----------------------------------------------------

    private List<FieldError> check(String type, String content, String key) throws IOException {
        return new SchemaValidatorImpl(catalog()).validate(type, JSON.readTree(content), JSON.readTree(key));
    }

    private static List<String> paths(List<FieldError> errors) {
        return errors.stream().map(FieldError::path).toList();
    }

    @Test
    void theCorrectOptionMustBeAnOptionOfTheQuestion() throws IOException {
        String content = "{\"stem\":\"s\",\"options\":[{\"key\":\"A\",\"text\":\"a\"},{\"key\":\"B\",\"text\":\"b\"},{\"key\":\"C\",\"text\":\"c\"},{\"key\":\"D\",\"text\":\"d\"}]}";
        assertEquals(List.of(), check("TOEIC_P5_INCOMPLETE_SENTENCE", content, "{\"slots\":[[\"D\"]]}"));
        assertEquals(List.of("answerKey.slots[0][0]"), paths(check("TOEIC_P5_INCOMPLETE_SENTENCE", content, "{\"slots\":[[\"E\"]]}")));
    }

    @Test
    void optionKeysAreUnique() throws IOException {
        String content = "{\"stem\":\"s\",\"options\":[{\"key\":\"A\",\"text\":\"a\"},{\"key\":\"A\",\"text\":\"b\"},{\"key\":\"C\",\"text\":\"c\"},{\"key\":\"D\",\"text\":\"d\"}]}";
        assertEquals(List.of("content.options[1].key"), paths(check("TOEIC_P5_INCOMPLETE_SENTENCE", content, "{\"slots\":[[\"C\"]]}")));
    }

    @Test
    void multipleChoiceNeedsAsManyCorrectOptionsAsItAsksFor() throws IOException {
        String content = "{\"stem\":\"s\",\"selectCount\":2,\"options\":[{\"key\":\"A\",\"text\":\"a\"},{\"key\":\"B\",\"text\":\"b\"},{\"key\":\"C\",\"text\":\"c\"}]}";
        assertEquals(List.of(), check("IELTS_R_MULTIPLE_CHOICE", content, "{\"slots\":[[\"A\",\"C\"]]}"));
        assertEquals(List.of("answerKey.slots[0]"), paths(check("IELTS_R_MULTIPLE_CHOICE", content, "{\"slots\":[[\"A\"]]}")));
    }

    @Test
    void matchingNeedsOneAnswerPerItemFromTheChoices() throws IOException {
        String content = "{\"items\":[{\"text\":\"p1\"},{\"text\":\"p2\"}],\"choices\":[{\"key\":\"i\",\"text\":\"h1\"},{\"key\":\"ii\",\"text\":\"h2\"}]}";
        assertEquals(List.of(), check("IELTS_R_MATCHING_HEADINGS", content, "{\"slots\":[[\"i\"],[\"ii\"]]}"));
        assertEquals(List.of("answerKey.slots"), paths(check("IELTS_R_MATCHING_HEADINGS", content, "{\"slots\":[[\"i\"]]}")));
        assertEquals(List.of("answerKey.slots[1][0]"), paths(check("IELTS_R_MATCHING_HEADINGS", content, "{\"slots\":[[\"i\"],[\"iv\"]]}")));
    }

    @Test
    void aCompletionNeedsOneAnswerPerBlank() throws IOException {
        String content = "{\"text\":\"Name [[1]] phone [[2]] and again [[2]]\"}";
        assertEquals(List.of(), check("IELTS_L_FORM_NOTE_TABLE_FLOW_CHART_SUMMARY_COMPLETION", content, "{\"slots\":[[\"a\"],[\"b\"]]}"));
        assertEquals(List.of("answerKey.slots"), paths(check("IELTS_L_FORM_NOTE_TABLE_FLOW_CHART_SUMMARY_COMPLETION", content, "{\"slots\":[[\"a\"]]}")));
        assertEquals(List.of("content.text"), paths(check("IELTS_L_SENTENCE_COMPLETION", "{\"text\":\"no blank here\"}", "{\"slots\":[[\"a\"]]}")));
    }

    @Test
    void aLabelAnswerPickedFromAListMustBeInTheList() throws IOException {
        String content = "{\"imageMediaId\":\"11111111-1111-4111-8111-111111111111\",\"labelNumber\":1,\"choices\":[{\"key\":\"A\",\"text\":\"a\"},{\"key\":\"B\",\"text\":\"b\"}]}";
        assertEquals(List.of(), check("IELTS_L_PLAN_MAP_DIAGRAM_LABELLING", content, "{\"slots\":[[\"B\"]]}"));
        assertEquals(List.of("answerKey.slots[0][0]"), paths(check("IELTS_L_PLAN_MAP_DIAGRAM_LABELLING", content, "{\"slots\":[[\"F\"]]}")));
    }

    // ---- the exam room never receives the solution side (I4) -------------------------------------------------------------------------

    /** Names a learner must not see before submitting. Lower-cased, compared exactly and as a prefix ("correctOption", "answerText"). */
    static final List<String> SOLUTION_FIELD_PREFIXES = List.of("transcript", "explanation", "rationale", "solution", "sampleanswer",
            "modelanswer", "sampleresponse", "correct", "iscorrect", "answer", "hint", "script");

    private static void propertyNames(JsonNode schema, String path, List<String> out) {
        if (schema == null || !schema.isObject()) {
            return;
        }
        for (Iterator<Map.Entry<String, JsonNode>> it = schema.path("properties").fields(); it.hasNext();) {
            Map.Entry<String, JsonNode> f = it.next();
            out.add(path + f.getKey());
            propertyNames(f.getValue(), path + f.getKey() + ".", out);
        }
        propertyNames(schema.path("items"), path + "[].", out);
        for (String combinator : List.of("allOf", "anyOf", "oneOf")) {
            schema.path(combinator).forEach(c -> propertyNames(c, path, out));
        }
        for (Iterator<Map.Entry<String, JsonNode>> it = schema.path("$defs").fields(); it.hasNext();) {
            propertyNames(it.next().getValue(), path, out);
        }
    }

    @Test
    void noContentSchemaDeclaresASolutionField() throws IOException {
        Map<String, SchemaFiles.SchemaFile> files = new SchemaFiles("classpath*:catalog/question-types/*.schema.json").load();
        assertEquals(31, files.size());
        List<String> offenders = new ArrayList<>();
        for (SchemaFiles.SchemaFile f : files.values()) {
            List<String> names = new ArrayList<>();
            propertyNames(f.content(), "", names);
            for (String n : names) {
                String leaf = n.substring(n.lastIndexOf('.') + 1).toLowerCase(java.util.Locale.ROOT);
                if (SOLUTION_FIELD_PREFIXES.stream().anyMatch(leaf::startsWith)) {
                    offenders.add(f.code() + " content." + n);
                }
            }
        }
        assertEquals(List.of(), offenders, "these fields would reach the exam room: put them in the answer key (the solution side)");
    }

    @Test
    void theGuardCatchesASolutionFieldWhereverItHides() throws IOException {
        List<String> names = new ArrayList<>();
        propertyNames(JSON.readTree("{\"properties\":{\"a\":{\"type\":\"array\",\"items\":{\"properties\":{\"explanation\":{}}}}}}"), "", names);
        assertEquals(List.of("a", "a.[].explanation"), names);
    }

    @Test
    void everyListeningTypeKeepsItsTranscriptOnTheSolutionSide() throws IOException {
        Map<String, SchemaFiles.SchemaFile> files = new SchemaFiles("classpath*:catalog/question-types/*.schema.json").load();
        int listening = 0;
        for (SchemaFiles.SchemaFile f : files.values()) {
            if (f.content().path("properties").has("audioMediaId")) {
                listening++;
                assertTrue(f.answerKey().path("properties").has("transcript"), f.code() + " has audio but no transcript in its solution side");
            }
        }
        assertEquals(10, listening, "6 IELTS Listening + TOEIC Parts 1-4");
    }

    @Test
    void aTranscriptInContentIsRefusedAndOneInTheAnswerKeyIsAccepted() throws IOException {
        String audio = "11111111-1111-4111-8111-111111111111";
        String content = "{\"audioMediaId\":\"" + audio + "\",\"options\":[{\"key\":\"A\"},{\"key\":\"B\"},{\"key\":\"C\"}]}";
        assertEquals(List.of(), check("TOEIC_P2_QUESTION_RESPONSE", content, "{\"slots\":[[\"C\"]],\"transcript\":\"Where is the exit?\"}"));
        List<FieldError> leaked = check("TOEIC_P2_QUESTION_RESPONSE", content.replace("\"options\"", "\"transcript\":\"x\",\"options\""), "{\"slots\":[[\"C\"]]}");
        assertEquals(List.of("content.transcript"), paths(leaked));
    }

    // ---- M5: blanks are exactly 1..N and map to slots by number; M6: a word limit for typed diagram labels --------------------------

    @Test
    void blanksAreNumberedExactlyOneToN() throws IOException {
        String type = "IELTS_L_SENTENCE_COMPLETION";
        assertEquals(List.of(), check(type, "{\"text\":\"a [[1]] b [[2]]\"}", "{\"slots\":[[\"x\"],[\"y\"]]}"));
        assertEquals(List.of(), check(type, "{\"text\":\"a [[2]] b [[1]]\"}", "{\"slots\":[[\"x\"],[\"y\"]]}"), "order in the text does not matter, numbers do");
        assertEquals(List.of("content.text"), paths(check(type, "{\"text\":\"a [[1]] b [[3]]\"}", "{\"slots\":[[\"x\"],[\"y\"]]}")), "[[1]] and [[3]] leave a hole");
        assertEquals(List.of("content.text"), paths(check(type, "{\"text\":\"a [[2]] b [[3]]\"}", "{\"slots\":[[\"x\"],[\"y\"]]}")), "numbering starts at 1");
        assertEquals(List.of("content.text"), paths(check(type, "{\"text\":\"a [[0]] b [[1]]\"}", "{\"slots\":[[\"x\"],[\"y\"]]}")), "0 is not a blank number");
        assertEquals(List.of("answerKey.slots"), paths(check(type, "{\"text\":\"a [[1]] b [[2]] c [[3]]\"}", "{\"slots\":[[\"x\"],[\"y\"]]}")), "slot n answers blank n: three blanks need three slots");
    }

    @Test
    void aTypedDiagramLabelCanCarryAWordLimit() throws IOException {
        String content = "{\"imageMediaId\":\"11111111-1111-4111-8111-111111111111\",\"labelNumber\":3,\"marker\":{\"x\":0.25,\"y\":0.5}}";
        assertEquals(List.of(), check("IELTS_R_DIAGRAM_LABEL_COMPLETION", content, "{\"slots\":[[\"library\"]],\"maxWords\":2}"));
        assertEquals(List.of("answerKey.maxWords"), paths(check("IELTS_R_DIAGRAM_LABEL_COMPLETION", content, "{\"slots\":[[\"library\"]],\"maxWords\":99}")));
        assertEquals(List.of("answerKey.maxWords"), paths(check("IELTS_R_DIAGRAM_LABEL_COMPLETION", content, "{\"slots\":[[\"library\"]],\"maxWords\":-1}")));
    }

    @Test
    void brokenSchemasAreRefusedWhenTheCatalogIsBuilt() throws IOException {
        List<QuestionTypeCatalog.TypeRow> rows = seededRows();
        Map<String, SchemaFiles.SchemaFile> files = new java.util.HashMap<>(new SchemaFiles("classpath*:catalog/question-types/*.schema.json").load());
        // a type without a file
        Map<String, SchemaFiles.SchemaFile> missing = new java.util.HashMap<>(files);
        missing.remove("IELTS_S_PART2");
        IllegalStateException e1 = assertThrows(IllegalStateException.class, () -> QuestionTypeCatalog.build(rows, missing));
        assertTrue(e1.getMessage().contains("IELTS_S_PART2"), e1.getMessage());
        // a file without a type
        Map<String, SchemaFiles.SchemaFile> extra = new java.util.HashMap<>(files);
        extra.put("TOEIC_P8_NEW", files.get("TOEIC_P5_INCOMPLETE_SENTENCE"));
        IllegalStateException e2 = assertThrows(IllegalStateException.class, () -> QuestionTypeCatalog.build(rows, extra));
        assertTrue(e2.getMessage().contains("TOEIC_P8_NEW"), e2.getMessage());
        // a schema that is not a schema
        Map<String, SchemaFiles.SchemaFile> broken = new java.util.HashMap<>(files);
        broken.put("TOEIC_P5_INCOMPLETE_SENTENCE", new SchemaFiles.SchemaFile("TOEIC_P5_INCOMPLETE_SENTENCE",
                JSON.readTree("{\"type\":5}"), files.get("TOEIC_P5_INCOMPLETE_SENTENCE").answerKey()));
        IllegalStateException e3 = assertThrows(IllegalStateException.class, () -> QuestionTypeCatalog.build(rows, broken));
        assertTrue(e3.getMessage().contains("TOEIC_P5_INCOMPLETE_SENTENCE"), e3.getMessage());
        // a kind the domain does not know
        List<QuestionTypeCatalog.TypeRow> badKind = new ArrayList<>(rows);
        QuestionTypeCatalog.TypeRow first = badKind.get(0);
        badKind.set(0, new QuestionTypeCatalog.TypeRow(first.code(), first.examCode(), first.section(), "MCQ_TRIPLE", first.excelTemplateId()));
        IllegalStateException e4 = assertThrows(IllegalStateException.class, () -> QuestionTypeCatalog.build(badKind, files));
        assertTrue(e4.getMessage().contains(first.code()), e4.getMessage());
    }
}
