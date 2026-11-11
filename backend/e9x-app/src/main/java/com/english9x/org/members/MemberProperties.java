package com.english9x.org.members;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

/**
 * {@code e9x.org.join.*} (self-set, spec 15.1). A join code has 8 characters of a 32-symbol alphabet (40 bits), far less than an invitation token, so
 * attempts are limited by address AND by person. Each is "<count>/<n><s|m|h|d>" of attempts that did not join; a success gives its attempt back.
 *
 * @param ipRateLimit   failed attempts per client address (a classroom shares one address, hence the larger allowance)
 * @param userRateLimit failed attempts per signed-in account
 */
@ConfigurationProperties("e9x.org.join")
public record MemberProperties(
        @DefaultValue("30/15m") String ipRateLimit,
        @DefaultValue("10/15m") String userRateLimit) {}
