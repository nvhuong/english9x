package com.english9x.workflow.internal;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

/**
 * {@code e9x.workflow.*}. The value is a self-set assumption (spec §15.1), not a decision of the project owner.
 *
 * @param maxBundleSize most versions one bundle may hold; keep it at least {@code e9x.content.import.max-rows}, since an
 *                      Excel import is submitted as one bundle
 */
@ConfigurationProperties("e9x.workflow")
public record WorkflowProperties(@DefaultValue("2000") int maxBundleSize) {}
