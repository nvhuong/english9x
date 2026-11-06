package com.english9x.platform.e2e;

import java.util.Map;
import org.springframework.context.annotation.Profile;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * Test-data API for the Playwright suite: {@code POST /api/test/seed/*}. Exists only under the Spring profile
 * {@code e2e} (see SeedDisabledIT: without it the route is a 404 and this bean is not in the context).
 *
 * <p>It ships only a health check today. Each later task adds its own seed endpoint here (a method on this class
 * or a sibling {@code @Profile("e2e")} controller under {@code /api/test/seed/}) that inserts through
 * {@link SeedDatabase}, plus the matching function in e2e/fixtures/seed.ts.
 */
@RestController
@Profile("e2e")
@RequestMapping("/api/test/seed")
class SeedController {

    private final SeedDatabase db;

    SeedController(SeedDatabase db) {
        this.db = db;
    }

    /** Proves the profile is on and shows which database login the seed code writes as. */
    @PostMapping("/ping")
    Map<String, Object> ping() {
        String login = db.jdbc().queryForObject("select current_user", String.class);
        return Map.of("status", "ok", "seedLogin", login);
    }
}
