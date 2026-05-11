package com.orochiverse.platform.common.security.jwt;

import java.time.Clock;
import java.time.Instant;
import java.util.Date;
import java.util.UUID;

import org.springframework.stereotype.Component;

import com.orochiverse.platform.common.security.keys.JwtKeyProvider;
import com.orochiverse.platform.common.security.principals.OperatorRole;
import com.orochiverse.platform.common.security.principals.TenantRole;
import com.orochiverse.platform.common.security.principals.UserKind;

import io.jsonwebtoken.Jwts;

/**
 * Builds and signs RS256 access tokens per the §5.1 contract.
 *
 * <p>The {@link Clock} is injected so tests can issue tokens at deterministic
 * timestamps without sleeping. In production it's the system UTC clock.
 *
 * <p>This class is intentionally <em>not</em> aware of users/tenants/auth —
 * it just turns a {@link AccessTokenClaims} (which a future
 * {@code AuthService} will assemble) into a compact JWT string. That keeps
 * the test surface tiny and the dependencies one-way.
 */
@Component
public class AccessTokenIssuer {

    private final JwtKeyProvider keys;
    private final JwtProperties props;
    private final Clock clock;

    public AccessTokenIssuer(JwtKeyProvider keys, JwtProperties props, Clock clock) {
        this.keys = keys;
        this.props = props;
        this.clock = clock;
    }

    /**
     * Issues a signed JWT for the given subject. The {@code issuedAt} and
     * {@code expiresAt} claims are set from the injected {@link Clock} plus
     * {@link JwtProperties#accessTokenTtl()}; the {@code jti} is a fresh
     * UUID; everything else comes from the caller.
     *
     * @return the compact serialized JWT string ({@code header.payload.sig})
     */
    public Issued issue(
            String userId,
            String email,
            UserKind kind,
            OperatorRole operatorRole,
            String activeTenantId,
            TenantRole tenantRole,
            int tokenVersion) {

        Instant now = clock.instant();
        Instant exp = now.plus(props.accessTokenTtl());
        String jti = UUID.randomUUID().toString();

        AccessTokenClaims claims = new AccessTokenClaims(
                props.issuer(), userId, email, kind, operatorRole,
                activeTenantId, tenantRole, tokenVersion, jti, now, exp);

        var builder = Jwts.builder()
                .header().keyId(keys.activeKeyId()).and()
                .issuer(claims.issuer())
                .subject(claims.userId())
                .id(claims.jti())
                .issuedAt(Date.from(claims.issuedAt()))
                .expiration(Date.from(claims.expiresAt()))
                .claim("email", claims.email())
                .claim("kind", claims.kind().name())
                .claim("tv", claims.tokenVersion());

        if (claims.operatorRole() != null) {
            builder.claim("opRole", claims.operatorRole().name());
        }
        if (claims.activeTenantId() != null) {
            builder.claim("tid", claims.activeTenantId());
        }
        if (claims.tenantRole() != null) {
            builder.claim("tRole", claims.tenantRole().name());
        }

        String token = builder.signWith(keys.signingKey(), Jwts.SIG.RS256).compact();
        return new Issued(token, claims);
    }

    /** Pair of (compact token, exact claims that were embedded). */
    public record Issued(String token, AccessTokenClaims claims) {}
}
