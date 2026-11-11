package com.english9x.identity.api;

import jakarta.servlet.http.HttpServletRequest;

/** The client address a rate limiter counts against: X-Forwarded-For is believed only from the configured trusted proxies. */
public interface ClientAddress {
    String of(HttpServletRequest request);
}
