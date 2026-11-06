package com.english9x.identity.internal;

import com.english9x.identity.api.events.UserRegistered;
import com.english9x.identity.internal.AuthDtos.RegisterRequest;
import com.english9x.identity.internal.AuthDtos.ResetConfirmRequest;
import com.english9x.identity.internal.AuthDtos.TokenPair;
import java.time.Clock;
import java.time.Instant;
import java.util.Locale;
import java.util.Optional;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * Registration, verification, login, refresh, password reset and Google hand-over.
 *
 * <p>Rules that hold everywhere here:
 * <ul>
 *   <li>No account enumeration: register / resend / reset-request answer identically for known and unknown emails, and
 *       login answers one generic 401 (an unknown email still pays for a bcrypt check).
 *   <li>Mail is sent after the commit and never throws into the request.
 *   <li>Nothing secret is logged: not passwords, not tokens, not JWTs.
 *   <li>Access tokens already issued stay valid until they expire (15 min) after a password reset or logout: they are
 *       stateless on purpose. Refresh tokens are what gets revoked.
 * </ul>
 */
@Service
class AuthService {

    private static final Logger LOG = LoggerFactory.getLogger(AuthService.class);

    private final AuthRepository repo;
    private final PasswordHasher hasher;
    private final AccessTokens accessTokens;
    private final AuthProperties props;
    private final Clock clock;
    private final AuthMailer mailer;
    private final ApplicationEventPublisher events;
    private final TransactionTemplate tx;
    private final OneTimeCodes codes;
    private final AttemptLimiter loginLimiter;
    private final AttemptLimiter resendLimiter;
    private final AttemptLimiter resetLimiter;
    private final AttemptLimiter registerLimiter;

    AuthService(AuthRepository repo, PasswordHasher hasher, AccessTokens accessTokens, AuthProperties props, Clock clock,
                AuthMailer mailer, ApplicationEventPublisher events, PlatformTransactionManager txManager, OneTimeCodes codes) {
        this.repo = repo;
        this.hasher = hasher;
        this.accessTokens = accessTokens;
        this.props = props;
        this.clock = clock;
        this.mailer = mailer;
        this.events = events;
        this.tx = new TransactionTemplate(txManager);
        this.codes = codes;
        this.loginLimiter = new AttemptLimiter(props.loginRateLimit(), clock);
        this.resendLimiter = new AttemptLimiter(props.resendRateLimit(), clock);
        this.resetLimiter = new AttemptLimiter(props.resetRateLimit(), clock);
        this.registerLimiter = new AttemptLimiter(props.registerRateLimit(), clock);
    }

    // ---- registration and email verification -----------------------------------------------------------------------

    void register(RegisterRequest r, String ip) {
        throttle(registerLimiter, ip, true);
        String email = normalise(r.email());
        String name = r.displayName().strip();
        String locale = "en".equals(r.locale()) ? "en" : "vi";
        String passwordHash = hasher.hash(r.password()); // always paid, also for an address that already exists
        Instant now = clock.instant();
        tx.executeWithoutResult(status -> {
            Optional<UUID> id = repo.createUser(email, name, passwordHash, locale, now, false, now);
            if (id.isPresent()) {
                String token = TokenCodec.newToken();
                repo.issueVerification(id.get(), TokenCodec.hash(token), now.plus(props.verificationTtl()), now);
                events.publishEvent(new UserRegistered(id.get(), email, now));
                afterCommit(() -> mailer.sendVerification(email, name, locale, token));
            } else {
                // The same 201 as a fresh sign-up; the owner of the address gets an informational mail with no token.
                repo.findLogin(email).ifPresent(row ->
                        afterCommit(() -> mailer.sendAlreadyRegistered(email, row.displayName(), row.locale())));
            }
        });
    }

    void verifyEmail(String token) {
        if (!TokenCodec.plausible(token)) {
            throw AuthProblems.invalidToken();
        }
        byte[] hash = TokenCodec.hash(token);
        AuthRepository.Consumed c = repo.consumeVerification(hash, clock.instant())
                .filter(x -> TokenCodec.same(x.storedHash(), hash))
                .orElseThrow(AuthProblems::invalidToken);
        LOG.info("email verified for user {}", c.userId());
    }

    /** Always silent: whether the address exists, or is already verified, is not revealed. */
    void resendVerification(String rawEmail, String ip) {
        String email = normalise(rawEmail);
        throttle(resendLimiter, ip + "|" + email, true);
        String token = TokenCodec.newToken();
        Instant now = clock.instant();
        repo.issueVerificationForEmail(email, TokenCodec.hash(token), now.plus(props.verificationTtl()), now)
                .ifPresent(r -> mailer.sendVerification(email, r.displayName(), r.locale(), token));
    }

    // ---- login and tokens -----------------------------------------------------------------------------------------------

    TokenPair login(String rawEmail, String password, String ip) {
        String email = normalise(rawEmail);
        String key = ip + "|" + email;
        throttle(loginLimiter, key, false);
        Optional<AuthRepository.LoginRow> row = repo.findLogin(email);
        boolean ok;
        if (row.isPresent() && row.get().passwordHash() != null) {
            ok = hasher.matches(password, row.get().passwordHash());
        } else {
            hasher.burn(password); // unknown email / Google-only account: same cost, same answer
            ok = false;
        }
        if (!ok) {
            loginLimiter.recordFailure(key);
            throw AuthProblems.invalidCredentials();
        }
        loginLimiter.reset(key);
        return issueTokens(row.get().userId(), UUID.randomUUID());
    }

    TokenPair refresh(String refreshToken) {
        if (!TokenCodec.plausible(refreshToken)) {
            throw AuthProblems.invalidRefreshToken();
        }
        Instant now = clock.instant();
        String next = TokenCodec.newToken();
        byte[] oldHash = TokenCodec.hash(refreshToken);
        // One database call, no surrounding transaction: when the token turns out to be a replay the revocation of the
        // whole family is committed on return, and we answer 401.
        AuthRepository.Rotation r = repo.rotate(oldHash, TokenCodec.hash(next), now.plus(props.refreshTokenTtl()), now,
                props.refreshReuseGrace());
        if (r.outcome() == AuthRepository.RotateOutcome.REUSED) {
            LOG.warn("refresh token reuse detected for user {}: token family {} revoked", r.userId(), r.familyId());
        }
        if ((r.outcome() != AuthRepository.RotateOutcome.ROTATED && r.outcome() != AuthRepository.RotateOutcome.ROTATED_CONCURRENT)
                || !TokenCodec.same(r.storedHash(), oldHash)) {
            throw AuthProblems.invalidRefreshToken();
        }
        return new TokenPair(accessTokens.issue(r.userId()), next, props.accessTokenTtl().toSeconds(), "Bearer");
    }

    void logout(String refreshToken) {
        if (TokenCodec.plausible(refreshToken)) {
            repo.revokeFamily(TokenCodec.hash(refreshToken), clock.instant());
        }
    }

    private TokenPair issueTokens(UUID userId, UUID family) {
        Instant now = clock.instant();
        String refresh = TokenCodec.newToken();
        repo.issueRefresh(userId, family, TokenCodec.hash(refresh), now.plus(props.refreshTokenTtl()), now);
        return new TokenPair(accessTokens.issue(userId), refresh, props.accessTokenTtl().toSeconds(), "Bearer");
    }

    // ---- password reset ----------------------------------------------------------------------------------------------------

    /** Always silent, like {@link #resendVerification}. */
    void requestPasswordReset(String rawEmail, String ip) {
        String email = normalise(rawEmail);
        throttle(resetLimiter, ip + "|" + email, true);
        String token = TokenCodec.newToken();
        Instant now = clock.instant();
        repo.issueReset(email, TokenCodec.hash(token), now.plus(props.resetTtl()), now)
                .ifPresent(r -> mailer.sendPasswordReset(email, r.displayName(), r.locale(), token));
    }

    void confirmPasswordReset(ResetConfirmRequest r) {
        if (!TokenCodec.plausible(r.token())) {
            throw AuthProblems.invalidToken();
        }
        byte[] hash = TokenCodec.hash(r.token());
        String newHash = hasher.hash(r.newPassword());
        AuthRepository.Consumed c = repo.confirmReset(hash, newHash, clock.instant())
                .filter(x -> TokenCodec.same(x.storedHash(), hash))
                .orElseThrow(AuthProblems::invalidToken);
        LOG.info("password reset for user {}: all refresh tokens revoked", c.userId());
    }

    // ---- Google ------------------------------------------------------------------------------------------------------------

    AuthRepository.OAuthResult oauthSignIn(String provider, String subject, String email, boolean emailVerified, String name,
                                           String locale) {
        Instant now = clock.instant();
        return tx.execute(status -> {
            AuthRepository.OAuthResult res = repo.oauthLogin(provider, subject, email == null ? "" : email, emailVerified,
                    name, "en".equals(locale) ? "en" : "vi", now);
            if (res.outcome() == AuthRepository.OAuthOutcome.CREATED) {
                events.publishEvent(new UserRegistered(res.userId(), normalise(email), now));
            }
            return res;
        });
    }

    String issueLoginCode(UUID userId) {
        return codes.issue(userId);
    }

    TokenPair exchangeLoginCode(String code) {
        UUID userId = codes.redeem(code).orElseThrow(AuthProblems::invalidLoginCode);
        return issueTokens(userId, UUID.randomUUID());
    }

    // ---- helpers ---------------------------------------------------------------------------------------------------------------

    /** @param count true: every call counts as an attempt; false: the caller records failures itself */
    private static void throttle(AttemptLimiter limiter, String key, boolean count) {
        if (limiter.blocked(key)) {
            throw AuthProblems.tooManyAttempts(limiter.retryAfterSeconds(key));
        }
        if (count) {
            limiter.recordFailure(key);
        }
    }

    static String normalise(String email) {
        return email == null ? "" : email.strip().toLowerCase(Locale.ROOT);
    }

    private static void afterCommit(Runnable r) {
        if (TransactionSynchronizationManager.isSynchronizationActive()) {
            TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
                @Override
                public void afterCommit() {
                    r.run();
                }
            });
        } else {
            r.run();
        }
    }
}
