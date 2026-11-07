package com.english9x.audit.internal;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

/**
 * {@code e9x.audit.*}. Values are self-set assumptions (spec section 15.1).
 *
 * @param defaultPageSize rows per page when the request does not say
 * @param maxPageSize     the most rows one request may ask for (a larger request is answered with this many)
 * @param maxDetailBytes  the most JSON bytes one record's detail may take; the database bounds it at 16384
 */
@ConfigurationProperties("e9x.audit")
record AuditProperties(
        @DefaultValue("50") int defaultPageSize,
        @DefaultValue("100") int maxPageSize,
        @DefaultValue("4096") int maxDetailBytes) {

    AuditProperties {
        if (maxPageSize < 1 || maxPageSize > 1000) {
            throw new IllegalArgumentException("e9x.audit.max-page-size must be between 1 and 1000");
        }
        if (defaultPageSize < 1 || defaultPageSize > maxPageSize) {
            throw new IllegalArgumentException("e9x.audit.default-page-size must be between 1 and e9x.audit.max-page-size");
        }
        if (maxDetailBytes < 64 || maxDetailBytes > 16384) {
            throw new IllegalArgumentException("e9x.audit.max-detail-bytes must be between 64 and 16384 (the database's bound)");
        }
    }
}
