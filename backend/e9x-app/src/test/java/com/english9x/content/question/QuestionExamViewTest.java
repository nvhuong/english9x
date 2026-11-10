package com.english9x.content.question;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.english9x.content.question.api.QuestionExamView;
import com.english9x.content.question.api.QuestionGroupExamView;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.IOException;
import java.math.BigDecimal;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;

/**
 * Global Constraint: a learner never receives an answer key or any solution-side field before submitting. The exam-room DTOs are serialised the way the
 * API will serialise them, for the content side of the valid example of every question type, and the JSON is searched for the names of the solution
 * side and for the very values of the answer key.
 */
class QuestionExamViewTest {

    static final ObjectMapper JSON = new ObjectMapper().registerModule(new com.fasterxml.jackson.datatype.jsr310.JavaTimeModule());
    static final List<String> FORBIDDEN = List.of("answer", "transcript", "sampleAnswer", "explanation", "solution", "notes");

    @Test
    void thePropertiesOfTheExamRoomDtosCannotHoldASolutionField() {
        for (Class<?> dto : List.of(QuestionExamView.class, QuestionGroupExamView.class)) {
            for (var c : dto.getRecordComponents()) {
                for (String word : FORBIDDEN) {
                    assertFalse(c.getName().toLowerCase().contains(word.toLowerCase()), dto.getSimpleName() + "." + c.getName());
                }
            }
        }
    }

    @Test
    void serialisedExamViewOfEveryTypeHasNoAnswerKeyNorSolutionField() throws IOException {
        List<Path> examples;
        try (Stream<Path> files = Files.list(Path.of("src/test/resources/catalog/examples"))) {
            examples = files.filter(p -> p.getFileName().toString().endsWith(".valid.json")).sorted().toList();
        }
        assertTrue(examples.size() >= 31, "one valid example per type: " + examples.size());
        for (Path file : examples) {
            JsonNode example = JSON.readTree(file.toFile());
            String code = file.getFileName().toString().replace(".valid.json", "");
            QuestionExamView view = new QuestionExamView(UUID.randomUUID(), UUID.randomUUID(), code.startsWith("TOEIC") ? "TOEIC" : "IELTS", code, example.get("content"), BigDecimal.ONE);
            String serialised = JSON.writeValueAsString(view);
            List<String> names = new ArrayList<>();
            collectNames(JSON.readTree(serialised), names);
            for (String name : names) {
                for (String word : FORBIDDEN) {
                    assertFalse(name.toLowerCase().contains(word.toLowerCase()), code + ": the exam-room JSON has a field named " + name);
                }
            }
            // and none of the values of the answer key leaks into it
            List<String> secrets = new ArrayList<>();
            collectTexts(example.get("answerKey"), secrets);
            for (String secret : secrets) {
                if (secret.length() > 12) {
                    assertFalse(serialised.contains(secret), code + ": the exam-room JSON repeats " + secret);
                }
            }
            assertTrue(serialised.contains("\"content\""), code);
        }
        QuestionGroupExamView group = new QuestionGroupExamView(UUID.randomUUID(), UUID.randomUUID(), "IELTS", JSON.readTree("{\"title\":\"t\",\"passage\":\"p\"}"), List.of(UUID.randomUUID()));
        String serialised = JSON.writeValueAsString(group);
        for (String word : FORBIDDEN) {
            assertFalse(serialised.toLowerCase().contains(word.toLowerCase()), serialised);
        }
    }

    private static void collectNames(JsonNode n, List<String> out) {
        if (n.isObject()) {
            n.fields().forEachRemaining(e -> {
                out.add(e.getKey());
                collectNames(e.getValue(), out);
            });
        } else if (n.isArray()) {
            n.forEach(c -> collectNames(c, out));
        }
    }

    private static void collectTexts(JsonNode n, List<String> out) {
        if (n.isTextual()) {
            out.add(n.asText());
        } else if (n.isContainerNode()) {
            n.forEach(c -> collectTexts(c, out));
        }
    }
}
