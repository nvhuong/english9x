package com.english9x.identity.internal;

import com.english9x.identity.api.AccountHousekeeping;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import jakarta.annotation.PostConstruct;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/** Settings without a safe default are checked at startup, so a misconfigured instance fails fast instead of mailing broken links. */
@Configuration(proxyBeanMethods = false)
class IdentityStartupChecks {

    private final String publicBaseUrl;
    private final String smtpHost;

    IdentityStartupChecks(@Value("${e9x.public-base-url:}") String publicBaseUrl, @Value("${spring.mail.host:}") String smtpHost) {
        this.publicBaseUrl = publicBaseUrl;
        this.smtpHost = smtpHost;
    }

    @PostConstruct
    void check() {
        if (!publicBaseUrl.matches("https?://[^\\s/]+(/.*)?")) {
            throw new IllegalStateException("e9x.public-base-url (env E9X_PUBLIC_BASE_URL) must be the public https URL of the site; "
                    + "it is used in emailed links and the Google redirect URI");
        }
        if (smtpHost.isBlank()) {
            throw new IllegalStateException("spring.mail.host (env E9X_SMTP_HOST) is required: account mail cannot be sent without it");
        }
    }

    /**
     * Housekeeping: tokens expired / used / revoked more than 7 days ago are deleted, and so are Google accounts abandoned at
     * the consent step (see {@link AccountHousekeeping}). One failing does not stop the other. Counts only in the log.
     */
    @Component
    static class Purge {
        private static final Logger LOG = LoggerFactory.getLogger(Purge.class);
        private final AuthRepository repo;
        private final AccountHousekeeping accounts;

        Purge(AuthRepository repo, AccountHousekeeping accounts) {
            this.repo = repo;
            this.accounts = accounts;
        }

        @Scheduled(initialDelayString = "PT10M", fixedDelayString = "PT6H")
        void purge() {
            try {
                int n = repo.purge();
                if (n > 0) {
                    LOG.info("purged {} stale auth tokens", n);
                }
            } catch (RuntimeException e) {
                LOG.warn("auth token purge failed: {}", e.getClass().getSimpleName());
            }
            try {
                accounts.purgeAccountsWithoutConsent(); // logs its own count
            } catch (RuntimeException e) {
                LOG.warn("no-consent account purge failed: {}", e.getClass().getSimpleName());
            }
        }
    }
}
