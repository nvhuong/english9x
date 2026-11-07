package com.english9x.identity.api;

/** Clean-up of the identity module. The scheduled housekeeping in {@code identity.internal} runs this every 6 hours. */
public interface AccountHousekeeping {

    /**
     * Deletes Google accounts abandoned at the consent step: no consent on record for longer than
     * {@code e9x.auth.no-consent-retention} and no user activity. See {@code auth_purge_no_consent} for the exact rule.
     *
     * @return how many accounts were deleted
     */
    int purgeAccountsWithoutConsent();
}
