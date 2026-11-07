package com.english9x.identity.internal;

/**
 * Outgoing account mail. Implementations must never throw into the caller and must never log a token or an address
 * body. Called after the database transaction committed. P1.8 (notification) may later take this over.
 */
interface AuthMailer {

    void sendVerification(String to, String displayName, String locale, String token);

    /** Somebody registered with an address that already has an account: tell the owner, give nobody a token. */
    void sendAlreadyRegistered(String to, String displayName, String locale);

    /**
     * Google sign-in was linked to an existing account: its password is void and its sessions ended. Whoever registered
     * that account may have been someone else, so the mail is told no name: the one on the account may be theirs.
     */
    void sendGoogleLinked(String to, String locale);

    void sendPasswordReset(String to, String displayName, String locale, String token);
}
