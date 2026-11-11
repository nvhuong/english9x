package com.english9x.grading.auto;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import com.english9x.domain.scoring.Answer;
import com.english9x.domain.scoring.AnswerKey;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.List;
import org.junit.jupiter.api.Test;

class AnswerMapperTest {

    private static final ObjectMapper JSON = new ObjectMapper();

    private static Answer answer(String json) throws Exception {
        return AnswerMapper.answer(JSON.readTree(json));
    }

    @Test
    void aStringIsOneValueAnArrayIsOnePerElement() throws Exception {
        assertEquals(Answer.of("B"), answer("\"B\""));
        assertEquals(Answer.of("A", "C"), answer("[\"A\",\"C\"]"));
        assertEquals(Answer.of("Smith", "0123456"), answer("{\"values\":[\"Smith\",\"0123456\"]}"));
        assertEquals(Answer.of("3", "true"), answer("[3,true]"));
    }

    @Test
    void anythingElseIsNoAnswerNotAnError() throws Exception {
        assertEquals(Answer.empty(), answer("null"));
        assertEquals(Answer.empty(), answer("\"\""));
        assertEquals(Answer.empty(), answer("{\"x\":1}"));
        assertEquals(Answer.of("", "B"), answer("[null,\"B\"]"), "a blank slot stays a blank slot, so the others keep their place");
        assertEquals(Answer.empty(), AnswerMapper.answer(null));
    }

    @Test
    void aKeyIsSlotsOfVariantsWithAnOptionalWordLimit() throws Exception {
        AnswerKey key = AnswerMapper.key(JSON.readTree("{\"slots\":[[\"Smith\"],[\"0123 456\",\"0123456\"]],\"maxWords\":2,\"transcript\":\"t\"}"), 1.5, "v1");
        assertEquals(List.of(List.of("Smith"), List.of("0123 456", "0123456")), key.slots());
        assertEquals(2, key.maxWords());
        assertEquals(1.5, key.points());
        assertEquals(0, AnswerMapper.key(JSON.readTree("{\"slots\":[[\"A\"]]}"), 1, "v1").maxWords());
    }

    @Test
    void aKeyTheDomainRefusesStopsTheScoringAndNamesTheVersionOnly() throws Exception {
        for (String bad : new String[] {"{}", "{\"slots\":[]}", "{\"slots\":[[]]}", "{\"slots\":[\"A\"]}", "{\"slots\":[[1]]}", "{\"slots\":[[\" \"]]}"}) {
            IllegalStateException e = assertThrows(IllegalStateException.class, () -> AnswerMapper.key(JSON.readTree(bad), 1, "version-42"), bad);
            assertEquals(true, e.getMessage().contains("version-42"), bad);
        }
    }
}
