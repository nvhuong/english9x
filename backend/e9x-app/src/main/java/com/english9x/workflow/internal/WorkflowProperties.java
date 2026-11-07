package com.english9x.workflow.internal;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

/**
 * {@code e9x.workflow.*}. The value is a self-set assumption (spec §15.1), not a decision of the project owner.
 *
 * @param maxBundleSize most versions one bundle may hold; 1 to 5000 (the database's bound); keep it at least
 *                      {@code e9x.content.import.max-rows}, since an Excel import is submitted as one bundle
 */
@ConfigurationProperties("e9x.workflow")
public record WorkflowProperties(@DefaultValue("2000") int maxBundleSize) {

    /** The database bounds review_bundles.member_count to this range; a value outside it must stop the start-up, not 500 later. */
    public static final int DATABASE_MAX = 5000;

    public WorkflowProperties {
        if (maxBundleSize < 1 || maxBundleSize > DATABASE_MAX) {
            throw new IllegalArgumentException("e9x.workflow.max-bundle-size must be between 1 and " + DATABASE_MAX);
        }
    }
}
