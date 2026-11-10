package com.english9x.content.importer;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Configuration;

@Configuration(proxyBeanMethods = false)
@EnableConfigurationProperties(ImportProperties.class)
class ImportConfiguration {

    /** A lot of drafts is submitted as one review bundle (spec 7.1): it must fit. The application refuses to start otherwise, like the other key checks. */
    ImportConfiguration(ImportProperties props, @Value("${e9x.workflow.max-bundle-size:2000}") int maxBundleSize) {
        if (props.maxRows() > maxBundleSize) {
            throw new IllegalStateException("e9x.content.import.max-rows (" + props.maxRows() + ") must not exceed e9x.workflow.max-bundle-size ("
                    + maxBundleSize + "): a lot of drafts is submitted for review as one bundle");
        }
    }
}
