package com.english9x.identity.internal;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.english9x.support.MutableClock;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.web.ErrorResponseException;

/** Behaviour of AuthService that an HTTP test cannot see: the work done for accounts that do not exist. */
class AuthServiceTest {

    private final AuthRepository repo = mock(AuthRepository.class);
    private final PasswordHasher hasher = mock(PasswordHasher.class);
    private final MutableClock clock = new MutableClock(Instant.parse("2026-11-07T00:00:00Z"));

    private AuthService service() {
        AuthProperties props = new AuthProperties("0123456789abcdef0123456789abcdef", "english9x", Duration.ofMinutes(15),
                Duration.ofDays(30), Duration.ofSeconds(10), "10/15m", "3/1h", "5/1h", "20/1h", Duration.ofHours(24),
                Duration.ofHours(1), Duration.ofSeconds(60), 4, 8, List.of(), "x <x@x.test>", null);
        return new AuthService(repo, hasher, mock(AccessTokens.class), props, clock, mock(AuthMailer.class),
                mock(ApplicationEventPublisher.class), mock(PlatformTransactionManager.class), new OneTimeCodes(clock, props));
    }

    @Test
    void anUnknownEmailStillPaysForABcryptCheck() {
        when(repo.findLogin("nobody@example.test")).thenReturn(Optional.empty());
        ErrorResponseException e = assertThrows(ErrorResponseException.class, () -> service().login("nobody@example.test", "Whatever-pass-1", "1.1.1.1"));
        assertEquals(401, e.getStatusCode().value());
        verify(hasher).burn("Whatever-pass-1");
        verify(hasher, never()).matches(anyString(), anyString());
    }

    @Test
    void aGoogleOnlyAccountAlsoPaysAndFailsTheSameWay() {
        when(repo.findLogin("g@example.test")).thenReturn(Optional.of(
                new AuthRepository.LoginRow(UUID.randomUUID(), null, true, "G", "vi", 1)));
        ErrorResponseException e = assertThrows(ErrorResponseException.class, () -> service().login("g@example.test", "Whatever-pass-1", "1.1.1.1"));
        assertEquals(401, e.getStatusCode().value());
        verify(hasher).burn("Whatever-pass-1");
    }

    @Test
    void aKnownEmailWithAWrongPasswordIsTheSameAnswer() {
        when(repo.findLogin("a@example.test")).thenReturn(Optional.of(
                new AuthRepository.LoginRow(UUID.randomUUID(), "$2a$04$hash", true, "A", "vi", 1)));
        when(hasher.matches("wrong-password", "$2a$04$hash")).thenReturn(false);
        ErrorResponseException e = assertThrows(ErrorResponseException.class, () -> service().login("a@example.test", "wrong-password", "1.1.1.1"));
        assertEquals(401, e.getStatusCode().value());
        assertEquals("urn:e9x:problem:invalid-credentials", e.getBody().getType().toString());
        verify(hasher, never()).burn(any());
    }

    @Test
    void registrationHashesThePasswordEvenForAnExistingAddress() {
        when(hasher.hash("Some-pass-123")).thenReturn("$2a$04$h");
        when(repo.createUser(anyString(), anyString(), anyString(), anyString(), any()))
                .thenReturn(Optional.empty());
        when(repo.findLogin("dup@example.test")).thenReturn(Optional.empty());
        PlatformTransactionManager tm = mock(PlatformTransactionManager.class);
        when(tm.getTransaction(any())).thenReturn(mock(org.springframework.transaction.TransactionStatus.class));
        AuthProperties props = new AuthProperties("0123456789abcdef0123456789abcdef", "english9x", Duration.ofMinutes(15),
                Duration.ofDays(30), Duration.ofSeconds(10), "10/15m", "3/1h", "5/1h", "20/1h", Duration.ofHours(24),
                Duration.ofHours(1), Duration.ofSeconds(60), 4, 8, List.of(), "x <x@x.test>", null);
        AuthService s = new AuthService(repo, hasher, mock(AccessTokens.class), props, clock, mock(AuthMailer.class),
                mock(ApplicationEventPublisher.class), tm, new OneTimeCodes(clock, props));
        s.register(new AuthDtos.RegisterRequest("dup@example.test", "Some-pass-123", "Dup", true, "vi"), "1.1.1.1");
        verify(hasher).hash("Some-pass-123");
    }
}
