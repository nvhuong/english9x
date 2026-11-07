package com.english9x.platform.web;

import org.springframework.core.MethodParameter;
import org.springframework.http.MediaType;
import org.springframework.http.ProblemDetail;
import org.springframework.http.converter.HttpMessageConverter;
import org.springframework.http.server.ServerHttpRequest;
import org.springframework.http.server.ServerHttpResponse;
import org.springframework.http.server.ServletServerHttpRequest;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.servlet.mvc.method.annotation.ResponseBodyAdvice;

/**
 * The {@code instance} of a problem body is the request path, and {@code /api/invitations/{token}/accept} has a secret in its
 * path. On those routes a problem has no {@code instance}, so nothing built from the body (a later error reporter, say) ever holds
 * the token. Spring fills it in just before writing, hence an advice that runs after that; the filter-level
 * {@code ProblemSecurityHandlers} asks {@link #mayEchoPath} itself.
 */
@RestControllerAdvice
public class ProblemInstance implements ResponseBodyAdvice<Object> {

    private static final String SECRET_IN_PATH = "/api/invitations/";

    public static boolean mayEchoPath(String requestUri) {
        return requestUri == null || !requestUri.startsWith(SECRET_IN_PATH);
    }

    @Override
    public boolean supports(MethodParameter returnType, Class<? extends HttpMessageConverter<?>> converterType) {
        return true;
    }

    @Override
    public Object beforeBodyWrite(Object body, MethodParameter returnType, MediaType contentType,
                                  Class<? extends HttpMessageConverter<?>> converterType, ServerHttpRequest request, ServerHttpResponse response) {
        if (body instanceof ProblemDetail problem && request instanceof ServletServerHttpRequest servlet
                && !mayEchoPath(servlet.getServletRequest().getRequestURI())) {
            problem.setInstance(null);
        }
        return body;
    }
}
