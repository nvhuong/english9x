package com.english9x.grading.ai;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.english9x.grading.ai.api.AiResult;
import com.english9x.grading.ai.api.Response;
import com.english9x.grading.ai.api.SkillVersion;
import com.english9x.grading.ai.api.Task;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.time.Duration;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;

/** The contract with the real Gemini API. Runs only when GEMINI_API_KEY is set (a separate CI job; it does not block a merge). */
@EnabledIfEnvironmentVariable(named = "GEMINI_API_KEY", matches = ".+")
class GeminiContractIT {

    @Test
    void realGeminiAnswersInTheSchemaOfTheSkill() throws Exception {
        ObjectMapper json = new ObjectMapper();
        String model = System.getenv().getOrDefault("E9X_GEMINI_MODEL", "gemini-2.5-flash");
        GeminiAiGrader grader = new GeminiAiGrader("https://generativelanguage.googleapis.com", System.getenv("GEMINI_API_KEY"), model, Duration.ofSeconds(60), 1, json);
        SkillVersion skill = new SkillVersion(UUID.randomUUID(), "Grade like an IELTS examiner.",
                json.readTree("[{\"code\":\"TA\",\"name\":\"Task Achievement\",\"min\":0,\"max\":9,\"step\":0.5},{\"code\":\"CC\",\"name\":\"Coherence\",\"min\":0,\"max\":9,\"step\":0.5}]"),
                "{{rubric}}\n{{samples}}\nQuestion: {{task}}\nAnswer: {{response}}", json.readTree("[]"),
                json.readTree("{\"type\":\"object\",\"properties\":{\"TA\":{\"type\":\"number\"},\"CC\":{\"type\":\"number\"},\"feedback\":{\"type\":\"string\"}},\"required\":[\"TA\",\"CC\",\"feedback\"]}"));
        AiResult r = grader.grade(skill, new Task("IELTS_W_TASK2", "Some people prefer to live in cities. Discuss."),
                new Response.Text("Cities offer jobs and services, but villages are quieter. In my opinion both have merits."));
        assertEquals(model, r.model());
        assertTrue(r.band() >= 0 && r.band() <= 9, "band " + r.band());
        assertTrue(r.inputTokens() > 0 && r.outputTokens() > 0);
        assertTrue(r.result().path("feedback").isTextual());
    }
}
