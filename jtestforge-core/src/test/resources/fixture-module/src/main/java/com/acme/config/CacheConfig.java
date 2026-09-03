package com.acme.config;

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * Fixture: a {@code @Configuration} whose behaviour <em>is</em> the wiring - the bean
 * exists only under a condition. Classifies as CONTEXT_SLICE, which is disabled by
 * default, so it must be skipped rather than generated for.
 */
@Configuration
public class CacheConfig {

    @Bean
    @ConditionalOnProperty(name = "acme.cache.enabled", havingValue = "true")
    public String cacheName() {
        return "orders";
    }
}
