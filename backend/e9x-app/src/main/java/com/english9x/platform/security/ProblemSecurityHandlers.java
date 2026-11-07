package com.english9x.platform.security;

import com.english9x.platform.web.ProblemInstance;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.core.AuthenticationException;
import org.springframework.security.web.AuthenticationEntryPoint;
import org.springframework.security.web.access.AccessDeniedHandler;
import org.springframework.stereotype.Component;

/**
 * 401 and 403 produced by Spring Security (entry point, access-denied handler, and errors thrown by our filters before
 * MVC is reached) in exactly the shape of {@code ProblemDetailsAdvice}: {@code application/problem+json} with
 * {@code type}, {@code title}, {@code status}, {@code detail}, {@code instance} (see {@link ProblemInstance}) and an empty {@code errors[]}. The text
 * is generic: it does not say whether a token was missing, malformed, expired or badly signed.
 */
@Component
public class ProblemSecurityHandlers implements AuthenticationEntryPoint, AccessDeniedHandler {

    private final ObjectMapper mapper;

    ProblemSecurityHandlers(ObjectMapper mapper) {
        this.mapper = mapper;
    }

    @Override
    public void commence(HttpServletRequest request, HttpServletResponse response, AuthenticationException ex) throws IOException {
        response.setHeader(HttpHeaders.WWW_AUTHENTICATE, "Bearer");
        write(request, response, HttpStatus.UNAUTHORIZED, "unauthorized", "Unauthorized", "Authentication is required.");
    }

    @Override
    public void handle(HttpServletRequest request, HttpServletResponse response, AccessDeniedException ex) throws IOException {
        write(request, response, HttpStatus.FORBIDDEN, "forbidden", "Forbidden", "You do not have access to this resource.");
    }

    /** For filters that refuse a request themselves. */
    public void write(HttpServletRequest request, HttpServletResponse response, HttpStatus status, String slug, String title,
                      String detail) throws IOException {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("type", "urn:e9x:problem:" + slug);
        body.put("title", title);
        body.put("status", status.value());
        body.put("detail", detail);
        if (ProblemInstance.mayEchoPath(request.getRequestURI())) {
            body.put("instance", request.getRequestURI());
        }
        body.put("errors", List.of());
        response.setStatus(status.value());
        response.setContentType(MediaType.APPLICATION_PROBLEM_JSON_VALUE);
        response.setCharacterEncoding(StandardCharsets.UTF_8.name());
        mapper.writeValue(response.getOutputStream(), body);
    }
}
