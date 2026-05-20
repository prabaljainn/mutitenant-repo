package com.orochiverse.platform.common.security.auth;

import java.util.ArrayList;
import java.util.Collection;
import java.util.List;

import org.springframework.security.core.GrantedAuthority;
import org.springframework.security.core.authority.SimpleGrantedAuthority;

import com.orochiverse.platform.common.security.jwt.AccessTokenClaims;
import com.orochiverse.platform.common.security.principals.UserKind;

/**
 * Maps a verified {@link AccessTokenClaims} to the Spring Security
 * {@link GrantedAuthority} list that {@code @PreAuthorize("hasRole(...)")},
 * {@code .hasRole(...)}, and Spring's method-security expression resolver
 * read.
 *
 * <p>Convention follows Spring's {@code "ROLE_"} prefix so the standard
 * {@code hasRole('OPERATOR_ADMIN')} works without configuring a custom
 * {@code RoleHierarchy}. We emit two authorities per principal:
 *
 * <ul>
 *   <li>A <b>kind</b> authority — {@code ROLE_OPERATOR} or
 *       {@code ROLE_TENANT_USER}. Lets endpoints split the operator console
 *       from tenant self-service without naming every role.</li>
 *   <li>A <b>role</b> authority — {@code ROLE_OPERATOR_ADMIN} /
 *       {@code ROLE_OPERATOR_SUPPORT} for operators, or
 *       {@code ROLE_ADMIN} / {@code ROLE_MEMBER} for tenant users.</li>
 * </ul>
 *
 * <p>Tenant context (the {@code tid} claim) is <em>not</em> encoded in
 * authorities — it lives on {@link com.orochiverse.platform.common.tenant.TenantContext}
 * and is bound by the JWT filter for the duration of the request. Mixing
 * tenant ID into authorities would explode the role set and force every
 * {@code @PreAuthorize} expression to re-implement tenancy.
 */
public final class AuthorityResolver {

    private AuthorityResolver() {}

    public static Collection<GrantedAuthority> resolve(AccessTokenClaims claims) {
        List<GrantedAuthority> out = new ArrayList<>(2);
        out.add(new SimpleGrantedAuthority("ROLE_" + claims.kind().name()));

        if (claims.kind() == UserKind.OPERATOR && claims.operatorRole() != null) {
            out.add(new SimpleGrantedAuthority("ROLE_" + claims.operatorRole().name()));
        }
        if (claims.kind() == UserKind.TENANT_USER && claims.tenantRole() != null) {
            out.add(new SimpleGrantedAuthority("ROLE_" + claims.tenantRole().name()));
        }
        return out;
    }
}
