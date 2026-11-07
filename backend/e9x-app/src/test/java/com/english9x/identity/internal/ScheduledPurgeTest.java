package com.english9x.identity.internal;

import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.english9x.identity.api.AccountHousekeeping;
import org.junit.jupiter.api.Test;

/** One purge failing does not stop the other, and nothing escapes into the scheduler. */
class ScheduledPurgeTest {

    private final AuthRepository repo = mock(AuthRepository.class);
    private final AccountHousekeeping accounts = mock(AccountHousekeeping.class);
    private final IdentityStartupChecks.Purge purge = new IdentityStartupChecks.Purge(repo, accounts);

    @Test
    void aFailedTokenPurgeStillPurgesAccounts() {
        when(repo.purge()).thenThrow(new IllegalStateException("db"));
        purge.purge();
        verify(accounts).purgeAccountsWithoutConsent();
    }

    @Test
    void aFailedAccountPurgeDoesNotEscape() {
        when(accounts.purgeAccountsWithoutConsent()).thenThrow(new IllegalStateException("db"));
        purge.purge();
        verify(repo).purge();
    }
}
