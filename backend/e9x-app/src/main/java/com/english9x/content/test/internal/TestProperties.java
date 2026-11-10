package com.english9x.content.test.internal;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

/**
 * {@code e9x.content.test.*} (spec 15.1). {@code defaultPageSize} and {@code maxPageSize}: the size of a page of the test list in the CMS when the request
 * does not say, and the most it may ask for; {@code maxGroupsPerPart}: the most question groups one part of a test may hold.
 */
@ConfigurationProperties("e9x.content.test")
record TestProperties(
        @DefaultValue("50") int defaultPageSize,
        @DefaultValue("100") int maxPageSize,
        @DefaultValue("100") int maxGroupsPerPart) {

    TestProperties {
        if (defaultPageSize < 1 || maxPageSize < defaultPageSize || maxPageSize > 500) {
            throw new IllegalStateException("e9x.content.test: 1 <= default-page-size <= max-page-size <= 500");
        }
        if (maxGroupsPerPart < 1 || maxGroupsPerPart > 500) {
            throw new IllegalStateException("e9x.content.test.max-groups-per-part must be between 1 and 500");
        }
    }

    int pageSize(Integer requested) {
        return requested == null ? defaultPageSize : Math.max(1, Math.min(requested, maxPageSize));
    }
}
