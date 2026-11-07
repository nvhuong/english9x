package com.english9x.identity.internal;

import io.swagger.v3.oas.annotations.Hidden;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * Reached only when Google sign-in is not configured (otherwise Spring's OAuth2 filter answers these paths before
 * MVC): a clean 404 problem instead of a broken redirect.
 */
@RestController
@Hidden
class GoogleDisabledController {

    @GetMapping({"/api/auth/oauth2/google", "/api/auth/oauth2/callback/google"})
    void disabled() {
        throw AuthProblems.googleDisabled();
    }
}
