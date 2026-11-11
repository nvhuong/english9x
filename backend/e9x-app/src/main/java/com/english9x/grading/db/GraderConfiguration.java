package com.english9x.grading.db;

import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration(proxyBeanMethods = false)
@EnableConfigurationProperties(GraderDataSourceProperties.class)
class GraderConfiguration {

    @Bean
    GraderDatabase graderDatabase(GraderDataSourceProperties properties) {
        return new GraderDatabase(properties);
    }
}
