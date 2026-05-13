package com.orochiverse.platform.common.security.auth;

import java.util.Collection;

import org.springframework.security.authentication.AbstractAuthenticationToken;
import org.springframework.security.core.GrantedAuthority;

import com.orochiverse.platform.common.security.jwt.AccessTokenClaims;

/**
 * The {@link org.springframework.security.core.Authentication} stored in
 * {@link org.springframework.security.core.context.SecurityContextHolder}
 * for every JWT-authenticated request.
 *
 * <p>It holds the verified {@link AccessTokenClaims} as both the
 * {@code principal} (so {@code @AuthenticationPrincipal AuthenticatedUser}
 * works in controller signatures) and the source of authorities (mapped
 * via {@link AuthorityResolver}).
 *
 * <p>{@code credentials} returns {@code null} — the bearer token is no
 * longer needed once verification succeeded; we don't keep it around to
 * avoid leaking it via toString() / debugger inspection.
 *
 * <p>{@code setAuthenticated(false)} is rejected: an instance of this
 * class is only ever constructed after a successful verification, and
 * downgrading it would silently disable subsequent authorization checks.
 */
public final class AuthenticatedUser extends AbstractAuthenticationToken {

    private final AccessTokenClaims claims;

    public AuthenticatedUser(AccessTokenClaims claims, Collection<? extends GrantedAuthority> authorities) {
        super(authorities);
        this.claims = claims;
        super.setAuthenticated(true);
    }

    public AccessTokenClaims claims() {
        return claims;
    }

    @Override
    public Object getPrincipal() {
        return this;
    }

    @Override
    public Object getCredentials() {
        return null;
    }

    @Override
    public String getName() {
        return claims.userId();
    }

    @Override
    public void setAuthenticated(boolean authenticated) {
        if (authenticated) {
            return;
        }
        throw new IllegalArgumentException(
                "AuthenticatedUser cannot be downgraded to unauthenticated; create a new context instead");
    }
}
