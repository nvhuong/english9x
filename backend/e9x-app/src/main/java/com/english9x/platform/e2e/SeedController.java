package com.english9x.platform.e2e;

import com.english9x.identity.api.UserDirectory;
import com.english9x.org.api.Authz;
import com.english9x.org.api.CurrentContextHolder;
import com.english9x.org.api.Role;
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
 * <p>Every request needs the shared secret header (see {@link SeedSecretInterceptor}); the application does not start
 * with this profile on a real domain (see {@link E2eGuard}). It ships a health check, verified/unverified learners
 * (P1.4), organisations, memberships and staff grants (P1.5) and a probe of the "email must be verified" guard. Each later task adds its own seed endpoint here (a method on this class
 * or a sibling {@code @Profile("e2e")} controller under {@code /api/test/seed/}) that inserts through
 * {@link SeedDatabase}, plus the matching function in e2e/fixtures/seed.ts.
 */
@RestController
@Profile("e2e")
@RequestMapping("/api/test/seed")
class SeedController {

    private final SeedDatabase db;
    private final UserDirectory users;
    private final Authz authz;
    // a low cost keeps seeding fast; the application verifies whatever cost the hash carries
    private final BCryptPasswordEncoder encoder = new BCryptPasswordEncoder(6);

    SeedController(SeedDatabase db, UserDirectory users, Authz authz) {
        this.db = db;
        this.users = users;
        this.authz = authz;
    }

    record LearnerRequest(String email, String password, String displayName, Boolean verified, String locale) {}

    /**
     * A learner with a known password. {@code verified} defaults to true. Created through the same SECURITY DEFINER
     * function as real sign-ups ({@code auth_create_user}); verifying is done here as the table owner, the function has no
     * such option. Returns the credentials to sign in with.
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
        UUID id = db.jdbc().queryForObject("select auth_create_user(?,?,?,?,?)", UUID.class,
                email, r.displayName() != null ? r.displayName() : "Seed Learner", encoder.encode(password),
                r.locale() != null ? r.locale() : "vi", java.sql.Timestamp.from(now));
        if (id == null) {
            throw new org.springframework.web.server.ResponseStatusException(HttpStatus.CONFLICT, "email already exists");
        }
        if (verified) {
            db.jdbc().update("update users set email_verified_at = ? where id = ?", java.sql.Timestamp.from(now), id);
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
                "select id::text as id, password_hash is not null as has_password, email_verified_at is not null as verified, consent_at is not null as has_consent, display_name, "
                        + "(select count(*) from auth_identities i where i.user_id = u.id) as identities "
                        + "from users u where lower(email) = lower(?)", ref.email());
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("count", rows.size());
        if (!rows.isEmpty()) {
            var r = rows.get(0);
            out.put("userId", r.get("id"));
            out.put("hasPassword", r.get("has_password"));
            out.put("emailVerified", r.get("verified"));
            out.put("hasConsent", r.get("has_consent"));
            out.put("displayName", r.get("display_name"));
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

    record OrgRequest(String name, String type) {}

    /** An organisation (CENTER by default). Created as the table owner: the E2E fixture needs no SYS_ADMIN session for this. */
    @PostMapping("/organization")
    @ResponseStatus(HttpStatus.CREATED)
    Map<String, Object> organization(@RequestBody(required = false) OrgRequest req) {
        String suffix = UUID.randomUUID().toString().substring(0, 6);
        String name = req != null && req.name() != null ? req.name() : "Org " + suffix;
        String type = req != null && req.type() != null ? req.type() : "CENTER";
        UUID id = db.jdbc().queryForObject("insert into organizations(name, type) values (?, ?) returning id", UUID.class, name, type);
        return Map.of("orgId", id.toString(), "name", name, "type", type);
    }

    record RoleRef(String role, String examCode) {}

    record MembershipRequest(UUID orgId, UUID userId, java.util.List<RoleRef> roles) {}

    /** Makes the user a member of the organisation (membership FIRST, as every real path does) with the given roles. */
    @PostMapping("/membership")
    @ResponseStatus(HttpStatus.CREATED)
    Map<String, Object> membership(@RequestBody MembershipRequest req) {
        db.jdbc().update("insert into org_memberships(org_id, user_id) values (?, ?) on conflict do nothing", req.orgId(), req.userId());
        if (req.roles() != null) {
            for (RoleRef r : req.roles()) {
                db.jdbc().update("insert into role_grants(user_id, org_id, exam_code, role) values (?, ?, ?, ?) on conflict on constraint role_grants_scope_uq do nothing",
                        req.userId(), req.orgId(), r.examCode(), r.role());
            }
        }
        return Map.of("status", "ok");
    }

    record StaffGrantRequest(UUID userId, String role, String examCode) {}

    /** An English9x-level grant (org NULL): AUTHOR / REVIEWER / TEACHER of an exam, or SYS_ADMIN (the seed stands in for the operator's script). */
    @PostMapping("/staff-grant")
    @ResponseStatus(HttpStatus.CREATED)
    Map<String, Object> staffGrant(@RequestBody StaffGrantRequest req) {
        db.jdbc().update("insert into role_grants(user_id, org_id, exam_code, role) values (?, null, ?, ?) on conflict on constraint role_grants_scope_uq do nothing",
                req.userId(), req.examCode(), req.role());
        return Map.of("status", "ok");
    }

    record AuthzProbe(UUID userId, UUID orgId, Role role, String examCode) {}

    /**
     * Asks the REAL {@code Authz} (the one the CMS and the other modules will call) what that person may do in a context: the
     * answer for "role R for exam X". Lets a spec prove exam-scoped roles on the server before the screens that use them exist.
     */
    @PostMapping("/authz")
    Map<String, Object> authz(@RequestBody AuthzProbe req) {
        try (CurrentContextHolder.Scope ignored = CurrentContextHolder.bind(req.userId(), req.orgId())) {
            Map<String, Object> out = new LinkedHashMap<>();
            out.put("allowed", authz.has(req.role(), java.util.Optional.ofNullable(req.examCode())));
            out.put("canReviewOwnVersion", authz.canReviewOwnVersion());
            return out;
        }
    }

    /** Proves the profile is on and shows which database login the seed code writes as. */
    @PostMapping("/ping")
    Map<String, Object> ping() {
        String login = db.jdbc().queryForObject("select current_user", String.class);
        return Map.of("status", "ok", "seedLogin", login);
    }
}
