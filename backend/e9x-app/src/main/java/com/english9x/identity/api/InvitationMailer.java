package com.english9x.identity.api;

import java.util.List;

/**
 * Sends an invitation (English9x staff or organisation role) by email. Same path as the account mail: SMTP on a bounded
 * executor, link built from {@code e9x.public-base-url} only, never from a request. Call it after the database commit.
 * Never throws and never logs the token.
 */
public interface InvitationMailer {

    /**
     * @param roles each entry {@code ROLE} or {@code ROLE/EXAM}, e.g. {@code AUTHOR/IELTS}, {@code ORG_ADMIN}
     * @param orgName the organisation's name, or null for an English9x (internal) invitation
     * @param locale the language to write in ("en", otherwise Vietnamese)
     */
    void sendInvitation(String to, String locale, String inviterName, String orgName, List<String> roles, String token);
}
