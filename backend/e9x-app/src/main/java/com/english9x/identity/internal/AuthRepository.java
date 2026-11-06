package com.english9x.identity.internal;

import java.time.Duration;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

/**
 * Every pre-authentication database operation. They all go through the SECURITY DEFINER functions of
 * V202611070001__identity_auth.sql (login lookup, token tables, account creation): the request role has no table
 * privilege on those tables, so this is the only door. Timestamps are passed in so one clock rules.
 */
@Repository
class AuthRepository {

    record LoginRow(UUID userId, String passwordHash, boolean emailVerified, String displayName, String locale) {}

    record Recipient(UUID userId, String displayName, String locale) {}

    record Consumed(UUID userId, byte[] storedHash) {}

    enum RotateOutcome { INVALID, ROTATED, ROTATED_CONCURRENT, REUSED }

    record Rotation(RotateOutcome outcome, UUID userId, UUID familyId, byte[] storedHash) {}

    enum OAuthOutcome { EXISTING, EMAIL_NOT_VERIFIED, LINKED, LINKED_NEUTRALISED, CREATED }

    record OAuthResult(OAuthOutcome outcome, UUID userId, String displayName, String locale) {}

    private final JdbcTemplate jdbc;

    AuthRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    private static OffsetDateTime ts(Instant i) {
        return OffsetDateTime.ofInstant(i, ZoneOffset.UTC);
    }

    /** New account, or empty when the email is taken. */
    Optional<UUID> createUser(String email, String displayName, String passwordHash, String locale, Instant consentAt,
                              boolean verified, Instant now) {
        UUID id = jdbc.queryForObject("select auth_create_user(?,?,?,?,?,?,?)", UUID.class,
                email, displayName, passwordHash, locale, ts(consentAt), verified, ts(now));
        return Optional.ofNullable(id);
    }

    Optional<LoginRow> findLogin(String email) {
        return jdbc.query("select * from auth_login_lookup(?)",
                (rs, n) -> new LoginRow(rs.getObject("r_user_id", UUID.class), rs.getString("r_password_hash"),
                        rs.getBoolean("r_email_verified"), rs.getString("r_display_name"), rs.getString("r_locale")),
                email).stream().findFirst();
    }

    void issueVerification(UUID userId, byte[] hash, Instant expires, Instant now) {
        jdbc.queryForList("select auth_email_verification_issue(?,?,?,?)", userId, hash, ts(expires), ts(now));
    }

    Optional<Recipient> issueVerificationForEmail(String email, byte[] hash, Instant expires, Instant now) {
        return jdbc.query("select * from auth_email_verification_issue_for_email(?,?,?,?)", RECIPIENT, email, hash, ts(expires), ts(now))
                .stream().findFirst();
    }

    Optional<Consumed> consumeVerification(byte[] hash, Instant now) {
        return jdbc.query("select * from auth_email_verification_consume(?,?)", CONSUMED, hash, ts(now)).stream().findFirst();
    }

    Optional<Recipient> issueReset(String email, byte[] hash, Instant expires, Instant now) {
        return jdbc.query("select * from auth_password_reset_issue(?,?,?,?)", RECIPIENT, email, hash, ts(expires), ts(now))
                .stream().findFirst();
    }

    Optional<Consumed> confirmReset(byte[] hash, String newPasswordHash, Instant now) {
        return jdbc.query("select * from auth_password_reset_confirm(?,?,?)", CONSUMED, hash, newPasswordHash, ts(now))
                .stream().findFirst();
    }

    void issueRefresh(UUID userId, UUID familyId, byte[] hash, Instant expires, Instant now) {
        jdbc.queryForList("select auth_refresh_issue(?,?,?,?,?)", userId, familyId, hash, ts(expires), ts(now));
    }

    Rotation rotate(byte[] oldHash, byte[] newHash, Instant newExpires, Instant now, Duration grace) {
        List<Rotation> rows = jdbc.query("select * from auth_refresh_rotate(?,?,?,?,cast(? as interval))",
                (rs, n) -> new Rotation(RotateOutcome.valueOf(rs.getString("r_outcome")),
                        rs.getObject("r_user_id", UUID.class), rs.getObject("r_family_id", UUID.class),
                        rs.getBytes("r_stored_hash")),
                oldHash, newHash, ts(newExpires), ts(now), grace.toMillis() + " milliseconds");
        return rows.isEmpty() ? new Rotation(RotateOutcome.INVALID, null, null, null) : rows.get(0);
    }

    void revokeFamily(byte[] hash, Instant now) {
        jdbc.queryForList("select auth_refresh_revoke_family(?,?)", hash, ts(now));
    }

    OAuthResult oauthLogin(String provider, String subject, String email, boolean emailVerified, String displayName,
                           String locale, Instant now) {
        return jdbc.queryForObject("select * from auth_oauth_login(?,?,?,?,?,?,?)",
                (rs, n) -> new OAuthResult(OAuthOutcome.valueOf(rs.getString("r_outcome")),
                        rs.getObject("r_user_id", UUID.class), rs.getString("r_display_name"), rs.getString("r_locale")),
                provider, subject, email, emailVerified, displayName, locale, ts(now));
    }

    int purge(Instant now, Duration older) {
        Integer n = jdbc.queryForObject("select auth_purge_expired(?, cast(? as interval))", Integer.class,
                ts(now), older.toSeconds() + " seconds");
        return n == null ? 0 : n;
    }

    private static final org.springframework.jdbc.core.RowMapper<Recipient> RECIPIENT = (rs, n) ->
            new Recipient(rs.getObject("r_user_id", UUID.class), rs.getString("r_display_name"), rs.getString("r_locale"));
    private static final org.springframework.jdbc.core.RowMapper<Consumed> CONSUMED = (rs, n) ->
            new Consumed(rs.getObject("r_user_id", UUID.class), rs.getBytes("r_stored_hash"));
}
