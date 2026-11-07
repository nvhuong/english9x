package com.english9x.org.internal;

import java.time.Duration;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

/**
 * {@code e9x.org.*}. Both values are self-set assumptions (spec §15.1), not decisions of the project owner.
 *
 * @param invitationTtl         how long an invitation link stays valid
 * @param invitationHourlyLimit invitations (mails) one person may send per hour; stops the invite form being used to spam
 *                              arbitrary addresses
 */
@ConfigurationProperties("e9x.org")
public record OrgProperties(
        @DefaultValue("7d") Duration invitationTtl,
        @DefaultValue("50") int invitationHourlyLimit) {}
