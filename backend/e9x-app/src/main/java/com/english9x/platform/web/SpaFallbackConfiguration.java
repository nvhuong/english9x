package com.english9x.platform.web;

import jakarta.servlet.http.HttpServletRequest;
import java.util.List;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.io.ClassPathResource;
import org.springframework.core.io.Resource;
import org.springframework.http.InvalidMediaTypeException;
import org.springframework.http.MediaType;
import org.springframework.web.servlet.config.annotation.ResourceHandlerRegistry;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;
import org.springframework.web.servlet.resource.PathResourceResolver;
import org.springframework.web.servlet.resource.ResourceResolverChain;

/**
 * Serves the React build from the jar (classpath:/static/) and gives browser deep links (/cms, /preferences)
 * index.html so the client router can take over. Registering "/**" here replaces Spring Boot's default static
 * handler for the same locations, so nothing else changes for real files.
 *
 * <p>The fallback applies only when ALL of these hold; everything else keeps its real 404 (problem+json):
 * the request is a GET/HEAD that reached the static handler (no controller matched), the Accept header
 * names text/html explicitly ("*&#47;*" and a missing header do not count: that is not a browser navigation),
 * the path is not under /api or /actuator, and the last path segment has no file extension (a missing
 * /assets/app.js must be a 404, not HTML that the browser then fails to parse as a script).
 */
@Configuration(proxyBeanMethods = false)
class SpaFallbackConfiguration implements WebMvcConfigurer {

    private static final String INDEX = "static/index.html";

    @Override
    public void addResourceHandlers(ResourceHandlerRegistry registry) {
        registry.addResourceHandler("/**")
                .addResourceLocations("classpath:/static/")
                .resourceChain(false) // no caching resolver: the result depends on the Accept header
                .addResolver(new SpaResolver());
    }

    static boolean isApiPath(String path) {
        String p = path.startsWith("/") ? path.substring(1) : path;
        return p.equals("api") || p.startsWith("api/") || p.equals("actuator") || p.startsWith("actuator/");
    }

    static boolean hasExtension(String path) {
        String last = path.substring(path.lastIndexOf('/') + 1);
        return last.contains(".");
    }

    static boolean acceptsHtmlExplicitly(String accept) {
        if (accept == null || accept.isBlank()) {
            return false;
        }
        try {
            for (MediaType t : MediaType.parseMediaTypes(accept)) {
                if (t.getType().equalsIgnoreCase("text") && t.getSubtype().equalsIgnoreCase("html")) {
                    return true;
                }
            }
        } catch (InvalidMediaTypeException e) {
            return false;
        }
        return false;
    }

    static final class SpaResolver extends PathResourceResolver {
        @Override
        protected Resource resolveResourceInternal(
                HttpServletRequest request, String requestPath, List<? extends Resource> locations, ResourceResolverChain chain) {
            Resource found = super.resolveResourceInternal(request, requestPath, locations, chain);
            if (found != null || request == null) {
                return found;
            }
            if (isApiPath(requestPath) || hasExtension(requestPath) || !acceptsHtmlExplicitly(request.getHeader("Accept"))) {
                return null;
            }
            Resource index = new ClassPathResource(INDEX);
            return index.exists() && index.isReadable() ? index : null;
        }
    }
}
