package com.english9x.notification.internal;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.security.SecureRandom;
import java.sql.Timestamp;
import java.time.Clock;
import java.time.Instant;
import java.util.Base64;
import java.util.regex.Pattern;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Telegram linking (spec §13). The person asks for a link ({@link #issue}): a 256-bit URL-safe random token, of which only the SHA-256
 * digest is stored, valid for {@code e9x.notification.telegram.link-ttl}, one active token per person. The bot's {@code /start <token>} reaches
 * the unauthenticated webhook, which calls {@link #consume}; the database function takes the token's row under a lock, so it works once.
 * The rule for a chat that already belongs to another account is in the migration header: it is refused and the owner must unlink first.
 */
@Service
class TelegramLinkService {

    /** What a token looks like (43 URL-safe characters); anything else is rejected before it is even hashed. */
    static final Pattern TOKEN = Pattern.compile("[A-Za-z0-9_-]{20,64}");

    private final JdbcTemplate jdbc;
    private final NotificationProperties.Telegram cfg;
    private final Clock clock;
    private final SecureRandom random = new SecureRandom();

    TelegramLinkService(JdbcTemplate jdbc, NotificationProperties props, Clock clock) {
        this.jdbc = jdbc;
        this.cfg = props.telegram();
        this.clock = clock;
    }

    record Issued(String deepLink, Instant expiresAt) {}

    /** {@code oldChat}: the chat this account had before a LINKED replaced it, else null. */
    record Consumed(String status, String locale, Long oldChat) {}

    boolean enabled() {
        return cfg.enabled();
    }

    /** For the signed-in person of the current context. */
    @Transactional
    Issued issue() {
        if (!cfg.enabled()) {
            throw NotificationProblems.telegramNotConfigured();
        }
        byte[] raw = new byte[32];
        random.nextBytes(raw);
        String token = Base64.getUrlEncoder().withoutPadding().encodeToString(raw);
        Instant now = clock.instant();
        Instant expires = now.plus(cfg.linkTtl());
        jdbc.queryForList("select telegram_link_issue(?, ?, ?)", digest(token), Timestamp.from(now), Timestamp.from(expires));
        return new Issued("https://t.me/" + cfg.botUsername() + "?start=" + token, expires);
    }

    @Transactional(readOnly = true)
    boolean linked() {
        return Boolean.TRUE.equals(jdbc.queryForObject("select exists (select 1 from telegram_links where user_id = e9x_current_user())", Boolean.class));
    }

    /** Removes the person's chat and retires their outstanding link tokens (one database function, rows of the caller only). */
    @Transactional
    void unlink() {
        jdbc.queryForList("select telegram_unlink()");
    }

    /** True when the linked chat is recorded but the latest delivery to it failed (for example the person blocked the bot). */
    @Transactional(readOnly = true)
    boolean failing() {
        return Boolean.TRUE.equals(jdbc.queryForObject("select telegram_delivery_failing()", Boolean.class));
    }

    /** Called by the webhook. INVALID covers an unknown, used, expired or malformed token alike. */
    @Transactional
    Consumed consume(String token, long chatId) {
        if (token == null || !TOKEN.matcher(token).matches()) {
            return new Consumed("INVALID", null, null);
        }
        return jdbc.query("select r_status, r_locale, r_old_chat from telegram_link_consume(?, ?, ?)",
                (rs, i) -> new Consumed(rs.getString(1), rs.getString(2), (Long) rs.getObject(3)), digest(token), chatId, Timestamp.from(clock.instant())).get(0);
    }

    static byte[] digest(String token) {
        try {
            return MessageDigest.getInstance("SHA-256").digest(token.getBytes(StandardCharsets.UTF_8));
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }
}
