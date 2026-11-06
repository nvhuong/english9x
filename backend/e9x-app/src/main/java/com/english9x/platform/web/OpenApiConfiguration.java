package com.english9x.platform.web;

import io.swagger.v3.core.converter.ModelConverters;
import io.swagger.v3.oas.models.Components;
import io.swagger.v3.oas.models.media.Content;
import io.swagger.v3.oas.models.media.MediaType;
import io.swagger.v3.oas.models.media.Schema;
import io.swagger.v3.oas.models.responses.ApiResponse;
import org.springdoc.core.customizers.OpenApiCustomizer;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/** Documents the error format once: schema {@code Problem} and a {@code default} problem+json response per operation. */
@Configuration(proxyBeanMethods = false)
class OpenApiConfiguration {

    @Bean
    OpenApiCustomizer problemResponses() {
        return openApi -> {
            if (openApi.getComponents() == null) {
                openApi.setComponents(new Components());
            }
            ModelConverters converters = ModelConverters.getInstance();
            converters.readAll(Problem.class).forEach(openApi.getComponents()::addSchemas);
            converters.readAll(ProblemError.class).forEach(openApi.getComponents()::addSchemas);
            Schema<?> ref = new Schema<>().$ref("#/components/schemas/Problem");
            if (openApi.getPaths() == null) {
                return;
            }
            openApi.getPaths().values().forEach(item -> item.readOperations().forEach(op -> {
                if (op.getResponses().getDefault() == null) {
                    op.getResponses().setDefault(new ApiResponse()
                            .description("Error (RFC 7807 problem details)")
                            .content(new Content().addMediaType("application/problem+json", new MediaType().schema(ref))));
                }
            }));
        };
    }
}
