package com.english9x.content.question.internal;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;
import org.springframework.util.unit.DataSize;

/**
 * {@code e9x.content.question.*} (spec 15.1). {@code maxJsonSize}: the most the content, and the answer key, of one question version may be
 * (each, serialised); {@code defaultPageSize} and {@code maxPageSize}: the size of a page of the question list when the request does not say,
 * and the most it may ask for; {@code maxGroupMembers}: the most questions one group version may hold.
 */
@ConfigurationProperties("e9x.content.question")
record QuestionProperties(
        @DefaultValue("256KB") DataSize maxJsonSize,
        @DefaultValue("50") int defaultPageSize,
        @DefaultValue("100") int maxPageSize,
        @DefaultValue("100") int maxGroupMembers) {

    QuestionProperties {
        if (maxJsonSize.toBytes() < 1024 || maxJsonSize.toBytes() > 8 * 1024 * 1024) {
            throw new IllegalStateException("e9x.content.question.max-json-size must be between 1KB and 8MB");
        }
        if (defaultPageSize < 1 || maxPageSize < defaultPageSize || maxPageSize > 500) {
            throw new IllegalStateException("e9x.content.question: 1 <= default-page-size <= max-page-size <= 500");
        }
        if (maxGroupMembers < 1 || maxGroupMembers > 500) {
            throw new IllegalStateException("e9x.content.question.max-group-members must be between 1 and 500");
        }
    }

    /** The page size to use: the requested one clamped to 1..max, the default when none was asked. */
    int pageSize(Integer requested) {
        return requested == null ? defaultPageSize : Math.max(1, Math.min(requested, maxPageSize));
    }
}
