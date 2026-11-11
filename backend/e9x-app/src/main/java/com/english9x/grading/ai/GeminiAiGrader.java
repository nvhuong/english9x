package com.english9x.grading.ai;

import com.english9x.grading.ai.api.AiGrader;
import com.english9x.grading.ai.api.AiGradingException;
import com.english9x.grading.ai.api.AiGradingException.Kind;
import com.english9x.grading.ai.api.AiResult;
import com.english9x.grading.ai.api.Response;
import com.english9x.grading.ai.api.SkillVersion;
import com.english9x.grading.ai.api.Task;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.networknt.schema.JsonSchema;
import com.networknt.schema.JsonSchemaFactory;
import com.networknt.schema.SpecVersion;
import com.networknt.schema.ValidationMessage;
import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Base64;
import java.util.List;
import java.util.Set;
import java.util.TreeSet;

/**
 * Gemini over its REST API ({@code models/{model}:generateContent}), one call per attempt. The key travels in the {@code x-goog-api-key} header and is never logged or put
 * in a message. A reply that is not JSON of the skill's schema (or has a criterion outside its range) is asked for again up to {@code schemaRetry} times, with the reasons in the
 * prompt; after that it is a {@link Kind#SCHEMA} failure. Timeouts, 429 and 5xx are {@link Kind#TRANSIENT} and are NOT retried here: the job queue retries the job after the
 * delays of {@code e9x.grading.ai.retry-delays}, so no worker thread sleeps for minutes.
 */
final class GeminiAiGrader implements AiGrader {

    private static final JsonSchemaFactory FACTORY = JsonSchemaFactory.getInstance(SpecVersion.VersionFlag.V202012);

    private final String baseUrl;
    private final String apiKey;
    private final String model;
    private final Duration timeout;
    private final int schemaRetry;
    private final ObjectMapper json;
    private final HttpClient http;

    GeminiAiGrader(String baseUrl, String apiKey, String model, Duration timeout, int schemaRetry, ObjectMapper json) {
        this.baseUrl = baseUrl.endsWith("/") ? baseUrl.substring(0, baseUrl.length() - 1) : baseUrl;
        this.apiKey = apiKey;
        this.model = model;
        this.timeout = timeout;
        this.schemaRetry = Math.max(0, schemaRetry);
        this.json = json;
        this.http = HttpClient.newBuilder().connectTimeout(timeout).build();
    }

    @Override
    public AiResult grade(SkillVersion skill, Task task, Response response) {
        if (apiKey == null || apiKey.isBlank()) {
            throw new AiGradingException(Kind.PERMANENT, "e9x.gemini.api-key is not set");
        }
        JsonSchema schema;
        try {
            schema = FACTORY.getSchema(skill.resultSchema());
        } catch (RuntimeException e) {
            throw new AiGradingException(Kind.PERMANENT, "the result schema of the grading skill does not compile", e);
        }
        List<String> refusals = new ArrayList<>();
        int inputTokens = 0;
        int outputTokens = 0;
        for (int attempt = 0; attempt <= schemaRetry; attempt++) {
            JsonNode reply = call(PromptRenderer.render(skill, task, response, refusals), response, skill.resultSchema());
            inputTokens += reply.path("usageMetadata").path("promptTokenCount").asInt(0);
            outputTokens += reply.path("usageMetadata").path("candidatesTokenCount").asInt(0);
            refusals = new ArrayList<>();
            JsonNode answer = answerOf(reply, refusals);
            if (answer != null) {
                refusals.addAll(problems(schema, skill.criteria(), answer));
            }
            if (refusals.isEmpty()) {
                return new AiResult(answer, Bands.of(skill.criteria(), answer), model, inputTokens, outputTokens);
            }
        }
        throw new AiGradingException(Kind.SCHEMA, "the answer broke the result schema after " + (schemaRetry + 1) + " attempts: " + String.join("; ", refusals));
    }

    private JsonNode call(String prompt, Response response, JsonNode resultSchema) {
        ObjectNode body = json.createObjectNode();
        ArrayNode parts = body.putArray("contents").addObject().put("role", "user").putArray("parts");
        parts.addObject().put("text", prompt);
        if (response instanceof Response.Audio a) {
            ObjectNode inline = parts.addObject().putObject("inline_data");
            inline.put("mime_type", a.mime());
            inline.put("data", Base64.getEncoder().encodeToString(a.bytes()));
        }
        ObjectNode config = body.putObject("generationConfig");
        config.put("responseMimeType", "application/json");
        config.set("responseJsonSchema", resultSchema);
        config.put("temperature", 0);
        HttpRequest request;
        try {
            request = HttpRequest.newBuilder(URI.create(baseUrl + "/v1beta/models/" + model + ":generateContent")).timeout(timeout)
                    .header("content-type", "application/json").header("x-goog-api-key", apiKey)
                    .POST(HttpRequest.BodyPublishers.ofString(json.writeValueAsString(body))).build();
        } catch (JsonProcessingException | IllegalArgumentException e) {
            throw new AiGradingException(Kind.PERMANENT, "the request could not be built", e);
        }
        HttpResponse<String> reply;
        try {
            reply = http.send(request, HttpResponse.BodyHandlers.ofString());
        } catch (IOException e) {
            throw new AiGradingException(Kind.TRANSIENT, "Gemini did not answer (" + e.getClass().getSimpleName() + ")", e);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new AiGradingException(Kind.TRANSIENT, "interrupted while waiting for Gemini", e);
        }
        int status = reply.statusCode();
        if (status == 429 || status >= 500) {
            throw new AiGradingException(Kind.TRANSIENT, "Gemini answered HTTP " + status);
        }
        if (status < 200 || status >= 300) {
            throw new AiGradingException(Kind.PERMANENT, "Gemini refused the request: HTTP " + status);
        }
        try {
            return json.readTree(reply.body());
        } catch (JsonProcessingException e) {
            throw new AiGradingException(Kind.TRANSIENT, "Gemini answered something that is not JSON", e);
        }
    }

    /** The JSON object in the candidate's text, or null with the reason added to {@code refusals}. */
    private JsonNode answerOf(JsonNode reply, List<String> refusals) {
        String text = reply.path("candidates").path(0).path("content").path("parts").path(0).path("text").asText("");
        if (text.isBlank()) {
            refusals.add("the answer was empty");
            return null;
        }
        try {
            JsonNode node = json.readTree(text);
            if (!node.isObject()) {
                refusals.add("the answer is not a JSON object");
                return null;
            }
            return node;
        } catch (JsonProcessingException e) {
            refusals.add("the answer is not valid JSON");
            return null;
        }
    }

    private static List<String> problems(JsonSchema schema, JsonNode criteria, JsonNode answer) {
        Set<String> out = new TreeSet<>();
        for (ValidationMessage m : schema.validate(answer)) {
            out.add(m.getMessage());
        }
        if (out.isEmpty() && criteria != null && criteria.isArray()) {
            for (JsonNode c : criteria) {
                JsonNode v = answer.path(c.path("code").asText());
                if (v.isNumber() && (v.asDouble() < c.path("min").asDouble() || v.asDouble() > c.path("max").asDouble())) {
                    out.add(c.path("code").asText() + " must be between " + c.path("min").asText() + " and " + c.path("max").asText());
                }
            }
        }
        return new ArrayList<>(out);
    }
}
