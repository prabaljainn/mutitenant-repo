package com.orochiverse.platform.common.security.jwt;

import org.springframework.stereotype.Component;

import com.orochiverse.platform.common.security.keys.JwtKeyProvider;
import com.orochiverse.platform.common.security.principals.OperatorRole;
import com.orochiverse.platform.common.security.principals.TenantRole;
import com.orochiverse.platform.common.security.principals.UserKind;

import io.jsonwebtoken.Claims;
import io.jsonwebtoken.JwtException;
import io.jsonwebtoken.Jwts;

/**
 * Verifies the signature, issuer, and lifetime of an access token, then
 * unpacks it back into {@link AccessTokenClaims}.
 *
 * <p>Phase 1.5 only checks what's intrinsic to the token. The
 * {@code tokenVersion} ({@code tv}) is parsed but <em>not</em> compared
 * against the user's current version — that lookup belongs in the Phase 1.6
 * security filter, which has access to the user repository / cache.
 */
@Component
public class AccessTokenVerifier {

    private final JwtKeyProvider keys;
    private final JwtProperties props;

    public AccessTokenVerifier(JwtKeyProvider keys, JwtProperties props) {
        this.keys = keys;
        this.props = props;
    }

    public AccessTokenClaims verify(String token) {
        if (token == null || token.isBlank()) {
            throw new JwtVerificationException("token is empty");
        }

        Claims c;
        try {
            c = Jwts.parser()
                    .verifyWith(keys.verificationKey())
                    .requireIssuer(props.issuer())
                    .clockSkewSeconds(props.clockSkew().toSeconds())
                    .build()
                    .parseSignedClaims(token)
                    .getPayload();
        } catch (JwtException e) {
            throw new JwtVerificationException("token verification failed: " + e.getMessage(), e);
        }

        try {
            UserKind kind = UserKind.valueOf(c.get("kind", String.class));
            String opRole = c.get("opRole", String.class);
            String tid = c.get("tid", String.class);
            String tRole = c.get("tRole", String.class);
            Integer tvBoxed = c.get("tv", Integer.class);
            int tv = tvBoxed == null ? 0 : tvBoxed;

            return new AccessTokenClaims(
                    c.getIssuer(),
                    c.getSubject(),
                    c.get("email", String.class),
                    kind,
                    opRole == null ? null : OperatorRole.valueOf(opRole),
                    tid,
                    tRole == null ? null : TenantRole.valueOf(tRole),
                    tv,
                    c.getId(),
                    c.getIssuedAt().toInstant(),
                    c.getExpiration().toInstant());
        } catch (IllegalArgumentException | NullPointerException e) {
            throw new JwtVerificationException("token claims are malformed: " + e.getMessage(), e);
        }
    }
}
