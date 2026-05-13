package com.orochiverse.platform.common.security.auth;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Instant;

import org.junit.jupiter.api.Test;
import org.springframework.security.core.GrantedAuthority;

import com.orochiverse.platform.common.security.jwt.AccessTokenClaims;
import com.orochiverse.platform.common.security.principals.OperatorRole;
import com.orochiverse.platform.common.security.principals.TenantRole;
import com.orochiverse.platform.common.security.principals.UserKind;

class AuthorityResolverTest {

    @Test
    void operator_admin_emits_kind_and_role_authorities() {
        var claims = operatorClaims(OperatorRole.OPERATOR_ADMIN, null);
        var auths = AuthorityResolver.resolve(claims).stream().map(GrantedAuthority::getAuthority).toList();

        assertThat(auths).containsExactlyInAnyOrder("ROLE_OPERATOR", "ROLE_OPERATOR_ADMIN");
    }

    @Test
    void operator_in_a_switched_tenant_does_not_get_a_tenant_role_authority() {
        var claims = operatorClaims(OperatorRole.OPERATOR_SUPPORT, "acme");
        var auths = AuthorityResolver.resolve(claims).stream().map(GrantedAuthority::getAuthority).toList();

        assertThat(auths).containsExactlyInAnyOrder("ROLE_OPERATOR", "ROLE_OPERATOR_SUPPORT");
    }

    @Test
    void tenant_user_emits_kind_and_tenant_role_authorities() {
        var claims = tenantUserClaims(TenantRole.TENANT_OWNER);
        var auths = AuthorityResolver.resolve(claims).stream().map(GrantedAuthority::getAuthority).toList();

        assertThat(auths).containsExactlyInAnyOrder("ROLE_TENANT_USER", "ROLE_TENANT_OWNER");
    }

    @Test
    void each_tenant_role_yields_a_distinct_authority() {
        for (TenantRole role : TenantRole.values()) {
            var auths = AuthorityResolver.resolve(tenantUserClaims(role)).stream()
                    .map(GrantedAuthority::getAuthority).toList();
            assertThat(auths).contains("ROLE_" + role.name());
        }
    }

    private static AccessTokenClaims operatorClaims(OperatorRole role, String tid) {
        return new AccessTokenClaims(
                "https://iam.test", "u-1", "x@example.com",
                UserKind.OPERATOR, role, tid, null,
                0, "jti-1", Instant.now(), Instant.now().plusSeconds(60));
    }

    private static AccessTokenClaims tenantUserClaims(TenantRole role) {
        return new AccessTokenClaims(
                "https://iam.test", "u-2", "y@example.com",
                UserKind.TENANT_USER, null, "acme", role,
                0, "jti-2", Instant.now(), Instant.now().plusSeconds(60));
    }
}
