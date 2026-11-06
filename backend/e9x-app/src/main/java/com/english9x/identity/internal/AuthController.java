package com.english9x.identity.internal;

import com.english9x.identity.api.UserDirectory;
import com.english9x.identity.internal.AuthDtos.AuthConfig;
import com.english9x.identity.internal.AuthDtos.EmailRequest;
import com.english9x.identity.internal.AuthDtos.LoginRequest;
import com.english9x.identity.internal.AuthDtos.Me;
import com.english9x.identity.internal.AuthDtos.OAuthCodeRequest;
import com.english9x.identity.internal.AuthDtos.RefreshRequest;
import com.english9x.identity.internal.AuthDtos.RegisterRequest;
import com.english9x.identity.internal.AuthDtos.RegisterResponse;
import com.english9x.identity.internal.AuthDtos.ResetConfirmRequest;
import com.english9x.identity.internal.AuthDtos.TokenPair;
import com.english9x.identity.internal.AuthDtos.TokenRequest;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

/** Public auth endpoints (see the explicit public list in SecurityConfiguration) plus {@code GET /api/auth/me}. */
@RestController
@RequestMapping("/api/auth")
class AuthController {

    private final AuthService auth;
    private final ClientIp clientIp;
    private final UserDirectory users;
    private final AuthProperties props;

    AuthController(AuthService auth, ClientIp clientIp, UserDirectory users, AuthProperties props) {
        this.auth = auth;
        this.clientIp = clientIp;
        this.users = users;
        this.props = props;
    }

    @GetMapping("/config")
    AuthConfig config() {
        return new AuthConfig(props.google().enabled());
    }

    @PostMapping("/register")
    @ResponseStatus(HttpStatus.CREATED)
    RegisterResponse register(@Valid @RequestBody RegisterRequest body, HttpServletRequest request) {
        auth.register(body, clientIp.of(request));
        return new RegisterResponse("pending_verification");
    }

    @PostMapping("/verify-email")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    void verifyEmail(@Valid @RequestBody TokenRequest body) {
        auth.verifyEmail(body.token());
    }

    @PostMapping("/verify-email/resend")
    @ResponseStatus(HttpStatus.ACCEPTED)
    void resend(@Valid @RequestBody EmailRequest body, HttpServletRequest request) {
        auth.resendVerification(body.email(), clientIp.of(request));
    }

    @PostMapping("/login")
    TokenPair login(@Valid @RequestBody LoginRequest body, HttpServletRequest request) {
        return auth.login(body.email(), body.password(), clientIp.of(request));
    }

    @PostMapping("/refresh")
    TokenPair refresh(@Valid @RequestBody RefreshRequest body) {
        return auth.refresh(body.refreshToken());
    }

    @PostMapping("/logout")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    void logout(@Valid @RequestBody RefreshRequest body) {
        auth.logout(body.refreshToken());
    }

    @PostMapping("/password-reset/request")
    @ResponseStatus(HttpStatus.ACCEPTED)
    void requestReset(@Valid @RequestBody EmailRequest body, HttpServletRequest request) {
        auth.requestPasswordReset(body.email(), clientIp.of(request));
    }

    @PostMapping("/password-reset/confirm")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    void confirmReset(@Valid @RequestBody ResetConfirmRequest body) {
        auth.confirmPasswordReset(body);
    }

    /** Redeems the one-time code that the Google callback put in the SPA redirect. */
    @PostMapping("/oauth/exchange")
    TokenPair exchange(@Valid @RequestBody OAuthCodeRequest body) {
        return auth.exchangeLoginCode(body.code());
    }

    /** The signed-in user (authenticated). */
    @GetMapping("/me")
    Me me(@AuthenticationPrincipal Jwt jwt) {
        UUID id = UUID.fromString(jwt.getSubject());
        return users.findById(id)
                .map(u -> new Me(u.id(), u.email(), u.displayName(), u.emailVerified(), u.locale()))
                .orElseThrow(AuthProblems::invalidCredentials);
    }
}
