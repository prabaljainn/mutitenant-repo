package com.orochiverse.platform.common.security.passwords;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.security.crypto.password.PasswordEncoder;

/**
 * Provides the singleton {@link PasswordEncoder} bean that
 * {@link PasswordHashing} (and any future Spring Security AuthenticationProvider)
 * will inject. Cost is pinned to {@link PasswordHashing#BCRYPT_COST}.
 */
@Configuration
public class PasswordHashingConfig {

    @Bean
    PasswordEncoder passwordEncoder() {
        return new BCryptPasswordEncoder(PasswordHashing.BCRYPT_COST);
    }
}
