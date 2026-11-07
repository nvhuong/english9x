package com.english9x.identity.internal;

import com.english9x.identity.api.AccountHousekeeping;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

@Service
class AccountHousekeepingImpl implements AccountHousekeeping {

    private static final Logger LOG = LoggerFactory.getLogger(AccountHousekeepingImpl.class);

    private final AuthRepository repo;
    private final AuthProperties props;

    AccountHousekeepingImpl(AuthRepository repo, AuthProperties props) {
        this.repo = repo;
        this.props = props;
    }

    @Override
    public int purgeAccountsWithoutConsent() {
        int n = repo.purgeNoConsent(props.noConsentRetention());
        if (n > 0) {
            LOG.info("deleted {} accounts abandoned at the consent step", n);
        }
        return n;
    }
}
