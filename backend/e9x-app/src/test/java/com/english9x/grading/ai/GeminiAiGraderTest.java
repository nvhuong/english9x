package com.english9x.grading.ai;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.english9x.grading.ai.api.AiGradingException;
import com.english9x.grading.ai.api.AiGradingException.Kind;
import com.english9x.grading.ai.api.AiResult;
import com.english9x.grading.ai.api.Response;
import com.english9x.grading.ai.api.SkillVersion;
import com.english9x.grading.ai.api.Task;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.sun.net.httpserver.HttpServer;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

/** The Gemini REST client against a fake HTTP server (JDK server on a free port; nothing is shared, so there is no lock to hold). */
@Timeout(30)
class GeminiAiGraderTest {

    private static final ObjectMapper JSON = new ObjectMapper();

    private HttpServer server;
    private final List<String> bodies = new CopyOnWriteArrayList<>();
    private final List<String> keys = new CopyOnWriteArrayList<>();
    private final List<Object[]> script = new CopyOnWriteArrayList<>(); // {status, body}, consumed in order; the last repeats
    private final AtomicInteger served = new AtomicInteger();

    @BeforeEach
    void start() throws Exception {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/", ex -> {
            bodies.add(new String(ex.getRequestBody().readAllBytes(), StandardCharsets.UTF_8));
            keys.add(ex.getRequestHeaders().getFirst("x-goog-api-key"));
            Object[] step = script.get(Math.min(served.getAndIncrement(), script.size() - 1));
            byte[] out = ((String) step[1]).getBytes(StandardCharsets.UTF_8);
            ex.getResponseHeaders().add("content-type", "application/json");
            ex.sendResponseHeaders((Integer) step[0], out.length);
            ex.getResponseBody().write(out);
            ex.close();
        });
        server.start();
    }

    @AfterEach
    void stop() {
        server.stop(0);
    }

    private GeminiAiGrader grader(int schemaRetry) {
        return new GeminiAiGrader("http://127.0.0.1:" + server.getAddress().getPort(), "test-key-123", "gemini-test", Duration.ofSeconds(5), schemaRetry, JSON);
    }

    private static String reply(String answerJson, int in, int out) {
        try {
            return JSON.writeValueAsString(JSON.createObjectNode().<com.fasterxml.jackson.databind.node.ObjectNode>set("candidates",
                    JSON.createArrayNode().add(JSON.createObjectNode().set("content", JSON.createObjectNode().set("parts", JSON.createArrayNode().add(JSON.createObjectNode().put("text", answerJson))))))
                    .set("usageMetadata", JSON.createObjectNode().put("promptTokenCount", in).put("candidatesTokenCount", out)));
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }

    private static SkillVersion skill() throws Exception {
        return new SkillVersion(UUID.randomUUID(), "Band descriptors",
                JSON.readTree("[{\"code\":\"TA\",\"name\":\"Task\",\"min\":0,\"max\":9,\"step\":0.5},{\"code\":\"CC\",\"name\":\"Coherence\",\"min\":0,\"max\":9,\"step\":0.5}]"),
                "Rubric: {{rubric}}\nSamples: {{samples}}\nTask: {{task}}\nEssay: {{response}}", JSON.readTree("[]"),
                JSON.readTree("{\"type\":\"object\",\"properties\":{\"TA\":{\"type\":\"number\"},\"CC\":{\"type\":\"number\"},\"feedback\":{\"type\":\"string\"}},\"required\":[\"TA\",\"CC\"]}"));
    }

    private static final Task TASK = new Task("IELTS_W_TASK2", "Discuss both views.");
    private static final Response ESSAY = new Response.Text("Some people think so.");

    @Test
    void validJsonParsed() throws Exception {
        script.add(new Object[] {200, reply("{\"TA\":6,\"CC\":7,\"feedback\":\"ok\"}", 120, 80)});
        AiResult r = grader(1).grade(skill(), TASK, ESSAY);
        assertEquals(6.5, r.band());
        assertEquals("gemini-test", r.model());
        assertEquals(120, r.inputTokens());
        assertEquals(80, r.outputTokens());
        assertEquals("ok", r.result().path("feedback").asText());
        assertEquals(1, bodies.size());
        assertEquals("test-key-123", keys.get(0), "the key travels in the header");
        JsonNode sent = JSON.readTree(bodies.get(0));
        String prompt = sent.path("contents").path(0).path("parts").path(0).path("text").asText();
        assertTrue(prompt.contains("Discuss both views.") && prompt.contains("Some people think so.") && prompt.contains("Band descriptors"), prompt);
        assertEquals("application/json", sent.path("generationConfig").path("responseMimeType").asText());
        assertEquals("object", sent.path("generationConfig").path("responseJsonSchema").path("type").asText());
        assertFalse(bodies.get(0).contains("test-key-123"), "the key is not in the body");
    }

    @Test
    void audioIsSentInline() throws Exception {
        script.add(new Object[] {200, reply("{\"TA\":5,\"CC\":5}", 1, 1)});
        grader(1).grade(skill(), new Task("IELTS_S_PART1", "Talk about home."), new Response.Audio(new byte[] {1, 2, 3}, "audio/webm"));
        JsonNode part = JSON.readTree(bodies.get(0)).path("contents").path(0).path("parts").path(1).path("inline_data");
        assertEquals("audio/webm", part.path("mime_type").asText());
        assertEquals("AQID", part.path("data").asText());
    }

    @Test
    void schemaViolationRetriedOnceWithErrors() throws Exception {
        script.add(new Object[] {200, reply("{\"TA\":6}", 100, 10)});            // CC missing
        script.add(new Object[] {200, reply("{\"TA\":6,\"CC\":5.5}", 150, 20)});
        AiResult r = grader(1).grade(skill(), TASK, ESSAY);
        assertEquals(2, bodies.size(), "asked again, once");
        assertFalse(bodies.get(0).contains("previous answer was refused"));
        String second = JSON.readTree(bodies.get(1)).path("contents").path(0).path("parts").path(0).path("text").asText();
        assertTrue(second.contains("previous answer was refused") && second.contains("CC"), "the validation errors are in the prompt: " + second);
        assertEquals(6.0, r.band());
        assertEquals(250, r.inputTokens(), "both calls are paid for");
        assertEquals(30, r.outputTokens());
    }

    @Test
    void schemaViolationAfterTheRetryFails() throws Exception {
        script.add(new Object[] {200, reply("{\"TA\":6}", 1, 1)});
        AiGradingException e = assertThrows(AiGradingException.class, () -> grader(1).grade(skill(), TASK, ESSAY));
        assertEquals(Kind.SCHEMA, e.kind());
        assertEquals(2, bodies.size());
        script.clear();
        script.add(new Object[] {200, reply("{\"TA\":12,\"CC\":5}", 1, 1)}); // out of the criterion's range
        served.set(0);
        assertEquals(Kind.SCHEMA, assertThrows(AiGradingException.class, () -> grader(0).grade(skill(), TASK, ESSAY)).kind());
    }

    @Test
    void serverErrorRetriedWithConfiguredDelays() throws Exception {
        // A 5xx / 429 is TRANSIENT and is not slept on inside the call: the job queue retries the job after the delays of e9x.grading.ai.retry-delays
        // (30s, 2m, 10m; RetryPolicyTest holds the alias, AiGradeJobIT the failed result and the terminal state). One call = one request.
        script.add(new Object[] {500, "{\"error\":{\"code\":500}}"});
        long t0 = System.nanoTime();
        AiGradingException e = assertThrows(AiGradingException.class, () -> grader(1).grade(skill(), TASK, ESSAY));
        assertEquals(Kind.TRANSIENT, e.kind());
        assertEquals(1, bodies.size(), "no hidden retry");
        assertTrue(Duration.ofNanos(System.nanoTime() - t0).compareTo(Duration.ofSeconds(3)) < 0, "and no sleeping");
        assertEquals("AiGradingException(TRANSIENT): Gemini answered HTTP 500", AiGradeJobHandler.describe(e));

        script.clear();
        served.set(0);
        script.add(new Object[] {429, "{}"});
        assertEquals(Kind.TRANSIENT, assertThrows(AiGradingException.class, () -> grader(1).grade(skill(), TASK, ESSAY)).kind());
        script.clear();
        served.set(0);
        script.add(new Object[] {403, "{}"});
        assertEquals(Kind.PERMANENT, assertThrows(AiGradingException.class, () -> grader(1).grade(skill(), TASK, ESSAY)).kind());
        // no key: nothing is sent
        int before = bodies.size();
        GeminiAiGrader keyless = new GeminiAiGrader("http://127.0.0.1:" + server.getAddress().getPort(), " ", "m", Duration.ofSeconds(1), 1, JSON);
        assertEquals(Kind.PERMANENT, assertThrows(AiGradingException.class, () -> keyless.grade(skill(), TASK, ESSAY)).kind());
        assertEquals(before, bodies.size());
    }

    @Test
    void aSlowServerIsATimeout() throws Exception {
        server.removeContext("/");
        server.createContext("/", ex -> {
            try {
                Thread.sleep(3000);
            } catch (InterruptedException ignored) {
                // the client gave up
            }
            ex.close();
        });
        GeminiAiGrader quick = new GeminiAiGrader("http://127.0.0.1:" + server.getAddress().getPort(), "k", "m", Duration.ofMillis(300), 1, JSON);
        AiGradingException e = assertThrows(AiGradingException.class, () -> quick.grade(skill(), TASK, ESSAY));
        assertEquals(Kind.TRANSIENT, e.kind());
        assertTrue(AiGradeJobHandler.describe(e).contains("<- HttpTimeoutException"), AiGradeJobHandler.describe(e));
    }
}
