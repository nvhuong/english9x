package com.english9x.notification.internal;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration(proxyBeanMethods = false)
@EnableConfigurationProperties(NotificationProperties.class)
class NotificationConfiguration {

    /** Message texts; links are built from the configured public base URL, never from a request. */
    @Bean
    Templates notificationTemplates(@Value("${e9x.public-base-url}") String publicBaseUrl) {
        return new Templates(publicBaseUrl);
    }
}
