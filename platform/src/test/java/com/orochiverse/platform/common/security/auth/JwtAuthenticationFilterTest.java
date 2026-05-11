package com.orochiverse.platform.common.security.auth;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.time.Instant;
import java.util.concurrent.atomic.AtomicReference;

import jakarta.servlet.FilterChain;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.security.core.context.SecurityContextHolder;

import com.orochiverse.platform.common.security.jwt.AccessTokenClaims;
import com.orochiverse.platform.common.security.jwt.AccessTokenVerifier;
import com.orochiverse.platform.common.security.jwt.JwtVerificationException;
import com.orochiverse.platform.common.security.principals.OperatorRole;
import com.orochiverse.platform.common.security.principals.TenantRole;
import com.orochiverse.platform.common.security.principals.UserKind;
import com.orochiverse.platform.common.tenant.TenantContext;

class JwtAuthenticationFilterTest {

    private AccessTokenVerifier verifier;
    private JwtAuthenticationFilter filter;
    private MockHttpServletRequest req;
    private MockHttpServletResponse res;
    private FilterChain chain;

    @BeforeEach
    void setUp() {
        verifier = mock(AccessTokenVerifier.class);
        filter = new JwtAuthenticationFilter(verifier);
        req = new MockHttpServletRequest("GET", "/anywhere");
        res = new MockHttpServletResponse();
        chain = mock(FilterChain.class);
    }

    @AfterEach
    void clearContext() {
        SecurityContextHolder.clearContext();
    }

    @Test
    void without_a_bearer_header_chain_runs_unauthenticated() throws Exception {
        filter.doFilter(req, res, chain);

        verify(chain).doFilter(req, res);
        assertThat(SecurityContextHolder.getContext().getAuthentication()).isNull();
        verify(verifier, never()).verify(any());
    }

    @Test
    void on_invalid_token_chain_still_runs_but_context_stays_empty() throws Exception {
        req.addHeader("Authorization", "Bearer rotten");
        when(verifier.verify("rotten")).thenThrow(new JwtVerificationException("expired"));

        filter.doFilter(req, res, chain);

        verify(chain).doFilter(req, res);
        assertThat(SecurityContextHolder.getContext().getAuthentication()).isNull();
    }

    @Test
    void operator_token_without_tid_does_not_bind_tenant_context() throws Exception {
        var claims = operator(null);
        req.addHeader("Authorization", "Bearer good");
        when(verifier.verify("good")).thenReturn(claims);

        var boundDuringChain = new AtomicReference<Boolean>();
        doAnswer(inv -> {
            boundDuringChain.set(TenantContext.isBound());
            return null;
        }).when(chain).doFilter(req, res);

        filter.doFilter(req, res, chain);

        verify(chain, times(1)).doFilter(req, res);
        assertThat(boundDuringChain.get()).isFalse();
        // Filter clears the security context after the chain runs.
        assertThat(SecurityContextHolder.getContext().getAuthentication()).isNull();
    }

    @Test
    void tenant_user_token_binds_tenant_context_for_the_chain() throws Exception {
        var claims = tenantUser("acme");
        req.addHeader("Authorization", "Bearer good");
        when(verifier.verify("good")).thenReturn(claims);

        var seenTid = new AtomicReference<String>();
        doAnswer(inv -> {
            seenTid.set(TenantContext.requireCurrent());
            return null;
        }).when(chain).doFilter(req, res);

        filter.doFilter(req, res, chain);

        assertThat(seenTid.get()).isEqualTo("acme");
        // Scope ends with the chain — it must be unbound now.
        assertThat(TenantContext.isBound()).isFalse();
    }

    @Test
    void operator_token_with_tid_also_binds_tenant_context() throws Exception {
        var claims = operator("vega");
        req.addHeader("Authorization", "Bearer good");
        when(verifier.verify("good")).thenReturn(claims);

        var seenTid = new AtomicReference<String>();
        doAnswer(inv -> {
            seenTid.set(TenantContext.requireCurrent());
            return null;
        }).when(chain).doFilter(req, res);

        filter.doFilter(req, res, chain);

        assertThat(seenTid.get()).isEqualTo("vega");
    }

    private static AccessTokenClaims operator(String tid) {
        return new AccessTokenClaims(
                "https://iam.test", "op-1", "op@example.com",
                UserKind.OPERATOR, OperatorRole.OPERATOR_ADMIN, tid, null,
                0, "jti-op", Instant.now(), Instant.now().plusSeconds(60));
    }

    private static AccessTokenClaims tenantUser(String tid) {
        return new AccessTokenClaims(
                "https://iam.test", "tu-1", "tu@example.com",
                UserKind.TENANT_USER, null, tid, TenantRole.ADMIN,
                0, "jti-tu", Instant.now(), Instant.now().plusSeconds(60));
    }
}
