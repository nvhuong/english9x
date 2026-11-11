package com.english9x.grading.ai;

import com.english9x.grading.ai.api.AiGrader;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.time.Duration;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration(proxyBeanMethods = false)
class AiConfiguration {

    /** The key comes from the environment ({@code E9X_GEMINI_API_KEY}); a blank key fails each grading as PERMANENT, never at start-up (the site works without AI). */
    @Bean
    AiGrader aiGrader(@Value("${e9x.gemini.base-url}") String baseUrl, @Value("${e9x.gemini.api-key:}") String apiKey, @Value("${e9x.gemini.model}") String model,
            @Value("${e9x.gemini.timeout}") Duration timeout, @Value("${e9x.grading.ai.schema-retry}") int schemaRetry, ObjectMapper json) {
        return new GeminiAiGrader(baseUrl, apiKey, model, timeout, schemaRetry, json);
    }
}
