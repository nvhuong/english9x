package com.english9x.billing.order;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration(proxyBeanMethods = false)
class OrderConfiguration {

    @Bean
    OrderCodes orderCodes() {
        return new OrderCodes.Random();
    }
}
