package com.english9x.billing.internal;

import com.english9x.platform.jobs.api.RecurringJob;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/** The billing properties and the three periodic jobs of the module (queued by the platform's scheduler, see RecurringJob). */
@Configuration(proxyBeanMethods = false)
@EnableConfigurationProperties(BillingProperties.class)
class BillingConfiguration {

    @Bean
    RecurringJob expireOrdersSchedule(BillingProperties props) {
        return new RecurringJob("expire_orders", props.jobInterval());
    }

    @Bean
    RecurringJob expireEntitlementsSchedule(BillingProperties props) {
        return new RecurringJob("expire_entitlements", props.jobInterval());
    }

    @Bean
    RecurringJob planExpiryReminderSchedule(BillingProperties props) {
        return new RecurringJob("plan_expiry_reminder", props.jobInterval());
    }
}
