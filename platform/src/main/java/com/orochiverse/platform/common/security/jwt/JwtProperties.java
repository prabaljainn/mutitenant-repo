package com.orochiverse.platform.common.security.jwt;

import java.nio.file.Path;
import java.time.Duration;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * Configuration for JWT issuance + RSA key loading.
 *
 * <p>Bound from the {@code platform.security.jwt} prefix:
 *
 * <pre>{@code
 *   platform:
 *     security:
 *       jwt:
 *         issuer: https://iam.orochiverse.com
 *         access-token-ttl: 15m
 *         clock-skew: 30s
 *         private-key-path:    # optional — when set, FileRsaKeyProvider is used
 *         public-key-path:     # optional
 *         key-id:              # required when paths are set
 * }</pre>
 *
 * <p>Leaving {@code private-key-path} unset selects the ephemeral provider —
 * appropriate for dev / test only. {@link com.orochiverse.platform.common.security.keys.JwtKeysConfig}
 * makes the choice via {@code @ConditionalOnProperty}.
 */
@ConfigurationProperties(prefix = "platform.security.jwt")
public record JwtProperties(
        String issuer,
        Duration accessTokenTtl,
        Duration clockSkew,
        Path privateKeyPath,
        Path publicKeyPath,
        String keyId) {

    public JwtProperties {
        if (issuer == null || issuer.isBlank()) {
            throw new IllegalArgumentException("platform.security.jwt.issuer must be set");
        }
        if (accessTokenTtl == null || accessTokenTtl.isNegative() || accessTokenTtl.isZero()) {
            throw new IllegalArgumentException("platform.security.jwt.access-token-ttl must be a positive duration");
        }
        if (clockSkew == null || clockSkew.isNegative()) {
            clockSkew = Duration.ofSeconds(30);
        }
    }
}
