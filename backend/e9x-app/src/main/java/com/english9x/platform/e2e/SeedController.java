package com.english9x.platform.e2e;

import com.english9x.identity.api.UserDirectory;
import com.english9x.org.api.CurrentContextHolder;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;
import org.springframework.context.annotation.Profile;
import org.springframework.http.HttpStatus;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

/**
 * Test-data API for the Playwright suite: {@code POST /api/test/seed/*}. Exists only under the Spring profile
 * {@code e2e} (see SeedDisabledIT: without it the route is a 404 and this bean is not in the context).
 *
 * <p>Every request needs the shared secret header (see {@link SeedSecretFilter}); the application does not start
 * with this profile on a real domain (see {@link E2eGuard}). It ships a health check, verified/unverified learners
 * (P1.4) and a probe of the "email must be verified" guard. Each later task adds its own seed endpoint here (a method on this class
 * or a sibling {@code @Profile("e2e")} controller under {@code /api/test/seed/}) that inserts through
 * {@link SeedDatabase}, plus the matching function in e2e/fixtures/seed.ts.
 */
@RestController
@Profile("e2e")
@RequestMapping("/api/test/seed")
class SeedController {

    private final SeedDatabase db;
    private final UserDirectory users;
    // a low cost keeps seeding fast; the application verifies whatever cost the hash carries
    private final BCryptPasswordEncoder encoder = new BCryptPasswordEncoder(6);

    SeedController(SeedDatabase db, UserDirectory users) {
        this.db = db;
        this.users = users;
    }

    record LearnerRequest(String email, String password, String displayName, Boolean verified, String locale) {}

    /**
     * A learner with a known password. {@code verified} defaults to true. Created through the same SECURITY DEFINER
     * function as real sign-ups ({@code auth_create_user}), as the table owner. Returns the credentials to sign in with.
     */
    @PostMapping("/learner")
    @ResponseStatus(HttpStatus.CREATED)
    Map<String, Object> learner(@RequestBody(required = false) LearnerRequest req) {
        LearnerRequest r = req == null ? new LearnerRequest(null, null, null, null, null) : req;
        String suffix = UUID.randomUUID().toString().substring(0, 8);
        String email = r.email() != null ? r.email() : "learner-" + suffix + "@e2e.english9x.test";
        String password = r.password() != null ? r.password() : "Seed-pass-" + suffix;
        boolean verified = r.verified() == null || r.verified();
        Instant now = Instant.now();
        UUID id = db.jdbc().queryForObject("select auth_create_user(?,?,?,?,?,?,?)", UUID.class,
                email, r.displayName() != null ? r.displayName() : "Seed Learner", encoder.encode(password),
                r.locale() != null ? r.locale() : "vi", java.sql.Timestamp.from(now), verified, java.sql.Timestamp.from(now));
        if (id == null) {
            throw new org.springframework.web.server.ResponseStatusException(HttpStatus.CONFLICT, "email already exists");
        }
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("userId", id.toString());
        out.put("email", email.toLowerCase());
        out.put("password", password);
        out.put("verified", verified);
        return out;
    }

    record EmailRef(String email) {}

    /** What the database holds about an address (for assertions that the UI cannot make): accounts, password, identities. */
    @PostMapping("/user-info")
    Map<String, Object> userInfo(@RequestBody EmailRef ref) {
        var rows = db.jdbc().queryForList(
                "select id::text as id, password_hash is not null as has_password, email_verified_at is not null as verified, "
                        + "(select count(*) from auth_identities i where i.user_id = u.id) as identities "
                        + "from users u where lower(email) = lower(?)", ref.email());
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("count", rows.size());
        if (!rows.isEmpty()) {
            var r = rows.get(0);
            out.put("userId", r.get("id"));
            out.put("hasPassword", r.get("has_password"));
            out.put("emailVerified", r.get("verified"));
            out.put("identities", ((Number) r.get("identities")).intValue());
        }
        return out;
    }

    record UserRef(UUID userId) {}

    /**
     * Calls the guard that billing (P5.1) uses before checkout, as that user: 200 when the email is verified, 403
     * problem {@code email-not-verified} when not.
     */
    @PostMapping("/require-verified")
    Map<String, Object> requireVerified(@RequestBody UserRef ref) {
        try (CurrentContextHolder.Scope ignored = CurrentContextHolder.bind(ref.userId(), null)) {
            users.requireVerified(ref.userId());
        }
        return Map.of("status", "verified");
    }

    /** Proves the profile is on and shows which database login the seed code writes as. */
    @PostMapping("/ping")
    Map<String, Object> ping() {
        String login = db.jdbc().queryForObject("select current_user", String.class);
        return Map.of("status", "ok", "seedLogin", login);
    }
}
