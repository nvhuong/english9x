package com.english9x.platform.security;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnWebApplication;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.annotation.Order;
import org.springframework.core.env.Environment;
import org.springframework.core.env.Profiles;
import org.springframework.http.HttpMethod;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configuration.EnableWebSecurity;
import org.springframework.security.config.annotation.web.configurers.HeadersConfigurer;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.security.oauth2.server.resource.web.authentication.BearerTokenAuthenticationFilter;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.header.writers.ReferrerPolicyHeaderWriter;
import org.springframework.security.web.util.matcher.AntPathRequestMatcher;
import org.springframework.security.web.util.matcher.RequestMatcher;

/**
 * The application's security: stateless, bearer-token (JWT in the {@code Authorization} header only).
 *
 * <ul>
 *   <li>{@code /api/**} requires a valid access token unless it is on the explicit public list below. Anything else
 *       under {@code /api} (including routes that do not exist yet) is therefore denied to anonymous callers.
 *   <li>Static files (the SPA) are public for GET/HEAD; every other method outside {@code /api} is denied.
 *       Actuator exposes {@code health} only (see application.yml) and that is public.
 *   <li>CSRF protection is off, which is acceptable ONLY because nothing authenticates through a cookie: the browser
 *       never sends the token by itself, a forged cross-site request carries no credential. Do not add cookie
 *       authentication without turning CSRF on. (The Google redirect chain uses a short-lived session cookie for the
 *       OAuth {@code state}, and the hand-over code is bound to a SameSite=Strict cookie that is worthless without that
 *       code; neither grants API access.)
 *   <li>CORS is off: the SPA is served from the same origin as the API, so no cross-origin response header is ever sent.
 *   <li>401/403 render as problem+json via {@link ProblemSecurityHandlers}.
 * </ul>
 */
@Configuration(proxyBeanMethods = false)
@ConditionalOnWebApplication(type = ConditionalOnWebApplication.Type.SERVLET) // some tests run without a web layer
@EnableWebSecurity
class SecurityConfiguration {

    /** Endpoints that need no token. Keep this list short and explicit; add new public endpoints here, with a test. */
    static final String[] PUBLIC_POST = {
        "/api/auth/register", "/api/auth/verify-email", "/api/auth/verify-email/resend", "/api/auth/login",
        "/api/auth/refresh", "/api/auth/logout", "/api/auth/password-reset/request", "/api/auth/password-reset/confirm",
        "/api/auth/oauth/exchange", "/api/auth/oauth/consent"
    };
    static final String[] PUBLIC_GET = {"/api/platform/ping", "/api/auth/config", "/v3/api-docs", "/v3/api-docs/**"};

    @Bean
    @Order(100)
    SecurityFilterChain apiSecurityFilterChain(HttpSecurity http, JwtDecoder jwtDecoder, ProblemSecurityHandlers problems,
            Environment env, @Value("${e9x.security.csp}") String csp) throws Exception {
        RequestMatcher seedApi = new AntPathRequestMatcher("/api/test/seed/**");
        boolean e2e = env.acceptsProfiles(Profiles.of("e2e"));
        http
                .csrf(c -> c.disable())
                .cors(c -> c.disable())
                .sessionManagement(s -> s.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
                .requestCache(c -> c.disable())
                .formLogin(f -> f.disable())
                .httpBasic(b -> b.disable())
                .logout(l -> l.disable())
                .headers(h -> h
                        .contentTypeOptions(c -> {})
                        .frameOptions(HeadersConfigurer.FrameOptionsConfig::deny)
                        .referrerPolicy(r -> r.policy(ReferrerPolicyHeaderWriter.ReferrerPolicy.NO_REFERRER))
                        .contentSecurityPolicy(c -> c.policyDirectives(csp))
                        .permissionsPolicyHeader(p -> p.policy("camera=(), geolocation=(), microphone=(self)"))
                        .cacheControl(c -> {}))
                .exceptionHandling(e -> e.authenticationEntryPoint(problems).accessDeniedHandler(problems))
                .oauth2ResourceServer(o -> o
                        .jwt(j -> j.decoder(jwtDecoder))
                        .authenticationEntryPoint(problems)
                        .accessDeniedHandler(problems))
                .addFilterAfter(new CurrentContextFilter(problems), BearerTokenAuthenticationFilter.class)
                .authorizeHttpRequests(a -> {
                    a.requestMatchers(HttpMethod.POST, PUBLIC_POST).permitAll();
                    a.requestMatchers(HttpMethod.GET, PUBLIC_GET).permitAll();
                    a.requestMatchers(HttpMethod.GET, "/actuator/health", "/actuator/health/**").permitAll();
                    if (e2e) {
                        a.requestMatchers(seedApi).permitAll(); // guarded by its own shared secret (platform.e2e)
                    }
                    a.requestMatchers("/api/**").authenticated();
                    a.requestMatchers("/actuator/**").denyAll();
                    a.requestMatchers(HttpMethod.GET, "/**").permitAll(); // the SPA's static files and deep links
                    a.requestMatchers(HttpMethod.HEAD, "/**").permitAll();
                    a.anyRequest().denyAll();
                });
        return http.build();
    }
}
