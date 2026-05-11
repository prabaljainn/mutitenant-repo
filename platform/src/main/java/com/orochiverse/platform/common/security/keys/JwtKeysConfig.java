package com.orochiverse.platform.common.security.keys;

import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import com.orochiverse.platform.common.security.jwt.JwtProperties;

/**
 * Selects which {@link JwtKeyProvider} implementation to wire based on
 * configuration:
 *
 * <ul>
 *   <li>If {@code platform.security.jwt.private-key-path} is set, the
 *       {@link FileRsaKeyProvider} loads the configured PEM files.</li>
 *   <li>Otherwise the {@link EphemeralRsaKeyProvider} generates a fresh
 *       keypair for this JVM. The provider's constructor logs a WARN so
 *       this is impossible to miss.</li>
 * </ul>
 *
 * <p>Both beans are guarded by {@code @ConditionalOnMissingBean(JwtKeyProvider.class)}
 * so a test can drop in a stub provider via a {@code @TestConfiguration} and
 * neither of these will fight it.
 */
@Configuration
public class JwtKeysConfig {

    @Bean
    @ConditionalOnProperty(prefix = "platform.security.jwt", name = "private-key-path")
    @ConditionalOnMissingBean(JwtKeyProvider.class)
    JwtKeyProvider fileRsaKeyProvider(JwtProperties props) {
        if (props.publicKeyPath() == null) {
            throw new IllegalStateException(
                    "platform.security.jwt.public-key-path must be set when private-key-path is set");
        }
        return new FileRsaKeyProvider(props.privateKeyPath(), props.publicKeyPath(), props.keyId());
    }

    @Bean
    @ConditionalOnMissingBean(JwtKeyProvider.class)
    JwtKeyProvider ephemeralRsaKeyProvider() {
        return new EphemeralRsaKeyProvider();
    }
}
