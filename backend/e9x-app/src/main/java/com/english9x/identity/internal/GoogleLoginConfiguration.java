package com.english9x.identity.internal;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import jakarta.servlet.http.HttpSession;
import java.io.IOException;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.List;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnExpression;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.annotation.Order;
import org.springframework.security.config.Customizer;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.AuthenticationException;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.oauth2.client.registration.ClientRegistration;
import org.springframework.security.oauth2.client.registration.ClientRegistrationRepository;
import org.springframework.security.oauth2.client.registration.InMemoryClientRegistrationRepository;
import org.springframework.security.oauth2.client.web.DefaultOAuth2AuthorizationRequestResolver;
import org.springframework.security.oauth2.client.web.OAuth2AuthorizationRequestCustomizers;
import org.springframework.security.oauth2.core.AuthorizationGrantType;
import org.springframework.security.oauth2.core.oidc.IdTokenClaimNames;
import org.springframework.security.oauth2.core.oidc.user.OidcUser;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.authentication.AuthenticationFailureHandler;
import org.springframework.security.web.authentication.AuthenticationSuccessHandler;

/**
 * Google sign-in with Spring's OAuth2 client (OpenID Connect, authorization code + PKCE).
 *
 * <ul>
 *   <li>{@code GET /api/auth/oauth2/google} starts it, {@code /api/auth/oauth2/callback/google} is the redirect URI
 *       (built from the configured public base URL, never from the request's Host).
 *   <li>Spring generates and checks {@code state} and the OIDC {@code nonce}, validates the ID token (signature from the
 *       JWKS, issuer, audience, expiry, nonce). The authorization request lives in the HTTP session of THIS chain only
 *       (everything else in the application is stateless); the session is invalidated as soon as the callback ends.
 *   <li>Without {@code e9x.auth.google.client-id} / {@code client-secret} nothing here exists: the route answers 404 and
 *       {@code GET /api/auth/config} says {@code googleEnabled:false}, so the SPA hides the button. Startup is unaffected.
 *   <li>The finished sign-in goes back to the SPA as a one-time code in the redirect (see {@link OneTimeCodes}).
 * </ul>
 */
@Configuration(proxyBeanMethods = false)
class GoogleLoginConfiguration {

    private static final Logger LOG = LoggerFactory.getLogger(GoogleLoginConfiguration.class);

    @Bean
    @ConditionalOnExpression("!'${e9x.auth.google.client-id:}'.isBlank() && !'${e9x.auth.google.client-secret:}'.isBlank()")
    ClientRegistrationRepository googleClientRegistrations(AuthProperties props, @Value("${e9x.public-base-url}") String baseUrl) {
        AuthProperties.Google g = props.google();
        List<String> scopes = g.scopes() == null || g.scopes().isBlank()
                ? List.of("openid", "email", "profile")
                : List.of(g.scopes().split("[ ,]+"));
        ClientRegistration google = ClientRegistration.withRegistrationId("google")
                .clientId(g.clientId())
                .clientSecret(g.clientSecret())
                .authorizationGrantType(AuthorizationGrantType.AUTHORIZATION_CODE)
                .redirectUri(trim(baseUrl) + "/api/auth/oauth2/callback/google")
                .scope(scopes)
                .authorizationUri(g.authorizationUri())
                .tokenUri(g.tokenUri())
                .jwkSetUri(g.jwkSetUri())
                .issuerUri(g.issuer())
                .userNameAttributeName(IdTokenClaimNames.SUB)
                .clientName("Google")
                .build();
        return new InMemoryClientRegistrationRepository(google);
    }

    /** The servlet security chain of the sign-in redirects; absent in a context without a web layer. */
    @Configuration(proxyBeanMethods = false)
    @org.springframework.boot.autoconfigure.condition.ConditionalOnWebApplication(
            type = org.springframework.boot.autoconfigure.condition.ConditionalOnWebApplication.Type.SERVLET)
    static class OAuthChain {

    @Bean
    @Order(1)
    SecurityFilterChain oauthSecurityFilterChain(HttpSecurity http,
            org.springframework.beans.factory.ObjectProvider<ClientRegistrationRepository> registrations, AuthService auth,
            @Value("${e9x.public-base-url}") String baseUrl) throws Exception {
        http.securityMatcher("/api/auth/oauth2/**")
                .csrf(c -> c.disable()) // GET-only redirects; state + the session bind callback to the start
                .sessionManagement(s -> s.sessionCreationPolicy(SessionCreationPolicy.IF_REQUIRED))
                .authorizeHttpRequests(a -> a.anyRequest().permitAll())
                .headers(Customizer.withDefaults());
        ClientRegistrationRepository repo = registrations.getIfAvailable();
        if (repo != null) {
            DefaultOAuth2AuthorizationRequestResolver resolver =
                    new DefaultOAuth2AuthorizationRequestResolver(repo, "/api/auth/oauth2");
            resolver.setAuthorizationRequestCustomizer(OAuth2AuthorizationRequestCustomizers.withPkce());
            http.oauth2Login(o -> o
                    .clientRegistrationRepository(repo)
                    .loginPage("/login")
                    .authorizationEndpoint(a -> a.baseUri("/api/auth/oauth2").authorizationRequestResolver(resolver))
                    .redirectionEndpoint(r -> r.baseUri("/api/auth/oauth2/callback/*"))
                    .successHandler(successHandler(auth, trim(baseUrl)))
                    .failureHandler(failureHandler(trim(baseUrl))));
        }
        return http.build();
    }
    }

    private static AuthenticationSuccessHandler successHandler(AuthService auth, String baseUrl) {
        return (HttpServletRequest request, HttpServletResponse response, Authentication authentication) -> {
            String target;
            try {
                if (!(authentication.getPrincipal() instanceof OidcUser oidc)) {
                    throw new IllegalStateException("not an OIDC sign-in");
                }
                String language = oidc.getLocale() == null ? null : oidc.getLocale().split("[-_]")[0].toLowerCase();
                AuthRepository.OAuthResult result = auth.oauthSignIn("google", oidc.getSubject(), oidc.getEmail(),
                        Boolean.TRUE.equals(oidc.getEmailVerified()), oidc.getFullName(), language);
                if (result.outcome() == AuthRepository.OAuthOutcome.EMAIL_NOT_VERIFIED || result.userId() == null) {
                    target = baseUrl + "/login?error=google_email_unverified";
                } else {
                    if (result.outcome() == AuthRepository.OAuthOutcome.LINKED_NEUTRALISED) {
                        LOG.warn("google sign-in linked an unverified local account {}: its password was removed and its sessions revoked",
                                result.userId());
                    }
                    target = baseUrl + "/login/google?code=" + URLEncoder.encode(auth.issueLoginCode(result.userId(), result.credentialsVersion()), StandardCharsets.UTF_8);
                }
            } catch (RuntimeException e) {
                LOG.warn("google sign-in failed: {}", e.getClass().getSimpleName());
                target = baseUrl + "/login?error=google";
            }
            finish(request, response, target);
        };
    }

    private static AuthenticationFailureHandler failureHandler(String baseUrl) {
        return (HttpServletRequest request, HttpServletResponse response, AuthenticationException exception) -> {
            // state / nonce / token problems all end here; log the class of failure, not the details
            LOG.warn("google sign-in rejected: {}", exception.getClass().getSimpleName());
            finish(request, response, baseUrl + "/login?error=google");
        };
    }

    /** The session served only the redirect round trip; drop it and keep the code out of caches and Referer headers. */
    private static void finish(HttpServletRequest request, HttpServletResponse response, String target) throws IOException {
        SecurityContextHolder.clearContext();
        HttpSession session = request.getSession(false);
        if (session != null) {
            session.invalidate();
        }
        response.setHeader("Cache-Control", "no-store");
        response.setHeader("Referrer-Policy", "no-referrer");
        response.sendRedirect(target);
    }

    private static String trim(String url) {
        return url.replaceAll("/+$", "");
    }
}
