package com.english9x.workflow.internal;

import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Configuration;

@Configuration(proxyBeanMethods = false)
@EnableConfigurationProperties(WorkflowProperties.class)
class WorkflowConfiguration {}
