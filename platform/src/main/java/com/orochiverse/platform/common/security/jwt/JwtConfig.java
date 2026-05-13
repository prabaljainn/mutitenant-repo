package com.orochiverse.platform.common.security.jwt;

import java.time.Clock;

import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * Activates {@link JwtProperties} binding and provides the default
 * {@link Clock} that {@link AccessTokenIssuer} uses for {@code iat}/{@code exp}.
 *
 * <p>The clock is exposed as a bean so tests can override it with
 * {@link Clock#fixed} to assert exact token timestamps without sleeping.
 */
@Configuration
@EnableConfigurationProperties(JwtProperties.class)
public class JwtConfig {

    @Bean
    @ConditionalOnMissingBean(Clock.class)
    Clock systemClock() {
        return Clock.systemUTC();
    }
}
