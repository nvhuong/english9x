package com.english9x.identity.api;

/**
 * Recurring clean-up of the identity module. Nothing runs this on a schedule yet: recurring jobs start with P5.1 (see the
 * dated note in that plan), which calls it.
 */
public interface AccountHousekeeping {

    /**
     * Deletes Google accounts abandoned at the consent step: no consent on record for longer than
     * {@code e9x.auth.no-consent-retention} and no user activity. See {@code auth_purge_no_consent} for the exact rule.
     *
     * @return how many accounts were deleted
     */
    int purgeAccountsWithoutConsent();
}
