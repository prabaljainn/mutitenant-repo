package com.orochiverse.platform.common.tenant;

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import com.mongodb.client.MongoClient;

/**
 * Wires the tenant-aware Mongo machinery into the Spring context.
 *
 * <p>The {@code @ConditionalOnProperty} guard means these beans only register
 * when {@code spring.data.mongodb.uri} is set — the same trigger Spring Boot
 * uses to decide whether to create a {@link MongoClient}. Profiles like
 * {@code test} that don't set a URI (and exclude Mongo autoconfig) skip this
 * config entirely, and that's intentional: code paths that don't touch Mongo
 * shouldn't have to run with Mongo up.
 *
 * <p>We can't use {@code @ConditionalOnBean(MongoClient.class)} here because
 * user {@code @Configuration} classes are evaluated before {@code MongoAuto-
 * Configuration} runs, so the bean check would always come up empty. The
 * property-based check is evaluated against the {@code Environment} at
 * config-processing time and so works reliably for both prod profiles and
 * tests using {@code @DynamicPropertySource}.
 */
@Configuration
@ConditionalOnProperty(prefix = "spring.data.mongodb", name = "uri")
public class TenantMongoConfig {

    @Bean
    public TenantMongoTemplateRegistry tenantMongoTemplateRegistry(MongoClient client) {
        return new TenantMongoTemplateRegistry(client);
    }

    @Bean
    public TenantDatabaseProvisioner tenantDatabaseProvisioner(
            MongoClient client, TenantMongoTemplateRegistry registry) {
        return new TenantDatabaseProvisioner(client, registry);
    }
}
