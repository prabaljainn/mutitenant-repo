package com.orochiverse.platform.common.security.auth;

import java.io.IOException;
import java.util.Optional;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.slf4j.MDC;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.web.filter.OncePerRequestFilter;

import com.orochiverse.platform.common.observability.RequestIdMdcFilter;
import com.orochiverse.platform.common.security.jwt.AccessTokenClaims;
import com.orochiverse.platform.common.security.jwt.AccessTokenVerifier;
import com.orochiverse.platform.common.security.jwt.JwtVerificationException;
import com.orochiverse.platform.common.tenant.TenantContext;

/**
 * Per-request filter that turns a Bearer JWT into an authenticated Spring
 * {@code SecurityContext} <em>and</em> binds the request's tenant context
 * for the rest of the chain.
 *
 * <h2>Behavior</h2>
 * <ul>
 *   <li><b>No header</b> → context stays empty; downstream
 *       {@code .anyRequest().authenticated()} causes Spring to invoke the
 *       {@link JsonAuthenticationEntryPoint} (HTTP 401).</li>
 *   <li><b>Invalid token</b> (bad signature, expired, wrong issuer,
 *       malformed claims) → same as above. We log the reason at DEBUG so
 *       legitimate token-rotation issues are diagnosable but the response
 *       body never leaks the parser error to the client.</li>
 *   <li><b>Valid token</b> → builds an {@link AuthenticatedUser}, sets it
 *       on {@link SecurityContextHolder}, and — if the {@code tid} claim
 *       is present — runs the rest of the chain inside
 *       {@link TenantContext#callIn} so any service called from the
 *       controller can resolve {@code TenantContext.requireCurrent()}
 *       without ever seeing the JWT.</li>
 * </ul>
 *
 * <h2>Why bind tenant context here, not in the controller?</h2>
 * Pushing it into the filter is the single move that lets developers write
 * code as if every API were single-tenant. The
 * {@link com.orochiverse.platform.common.tenant.TenantMongoTemplateRegistry}
 * reads {@code TenantContext.requireCurrent()} to pick the right per-tenant
 * Mongo database; if we bound the context in each controller we'd have to
 * remember to do it everywhere and would forget exactly once.
 *
 * <h2>What this filter deliberately doesn't do</h2>
 * <ul>
 *   <li><b>Token-version check</b>. The {@code tv} claim is parsed but
 *       not yet compared against the user's current version — that lookup
 *       belongs in Phase 1.10's revocation cache and lands when we have a
 *       Redis/repo round-trip to perform without creating a per-request
 *       Mongo hit. Until then, password changes invalidate refresh tokens
 *       (forcing re-login within the 15-min access TTL) but do not yank
 *       in-flight access tokens early.</li>
 *   <li><b>Operator-tenant-assignment check</b>. When an operator's
 *       {@code tid} claim is set, we trust the {@code switch-tenant} flow
 *       (Phase 1.7) to have validated the assignment at issue time. Re-
 *       validating on every request would defeat the stateless point of
 *       JWT.</li>
 * </ul>
 */
public class JwtAuthenticationFilter extends OncePerRequestFilter {

    private static final Logger log = LoggerFactory.getLogger(JwtAuthenticationFilter.class);

    private final AccessTokenVerifier verifier;
    /**
     * Optional: only present when {@code spring.data.mongodb.uri} is set,
     * because that's where the resolver reads the user's current tv from.
     * In the {@code test} profile (no Mongo) the bean doesn't exist and
     * we skip the check — fine because there's no user store to consult
     * and unit tests don't care.
     */
    private final TokenVersionLookup tvResolver;
    private final com.orochiverse.platform.common.observability.AuthMetrics metrics;

    public JwtAuthenticationFilter(AccessTokenVerifier verifier,
                                   org.springframework.beans.factory.ObjectProvider<TokenVersionLookup> tvResolver,
                                   com.orochiverse.platform.common.observability.AuthMetrics metrics) {
        this.verifier = verifier;
        this.tvResolver = tvResolver.getIfAvailable();
        this.metrics = metrics;
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request,
                                    HttpServletResponse response,
                                    FilterChain chain) throws ServletException, IOException {

        Optional<String> bearer = BearerTokenExtractor.extract(request);
        if (bearer.isEmpty()) {
            chain.doFilter(request, response);
            return;
        }

        AccessTokenClaims claims;
        try {
            claims = verifier.verify(bearer.get());
        } catch (JwtVerificationException e) {
            // Don't leak parser detail to the client — Spring will return
            // 401 via the configured entry point because the SecurityContext
            // stays empty.
            log.debug("Rejecting bearer token: {}", e.getMessage());
            chain.doFilter(request, response);
            return;
        }

        // Token-version revocation check (Phase 1.10). When the user's
        // current tv has moved past the claim's tv, the token was issued
        // before a password change / suspension and must be rejected.
        if (tvResolver != null) {
            int currentTv = tvResolver.currentVersion(claims.userId());
            if (currentTv != claims.tokenVersion()) {
                log.debug("Rejecting bearer for user {}: claim tv={} != current tv={}",
                        claims.userId(), claims.tokenVersion(), currentTv);
                metrics.tokenVersionMismatch();
                chain.doFilter(request, response);
                return;
            }
        }

        var auth = new AuthenticatedUser(claims, AuthorityResolver.resolve(claims));
        SecurityContextHolder.getContext().setAuthentication(auth);

        // Populate the user/tenant MDC slots reserved by the logback pattern.
        // RequestIdMdcFilter owns requestId; we own these two and clear
        // them in finally so they don't leak across requests.
        MDC.put(RequestIdMdcFilter.MDC_USER_ID, claims.userId());
        if (claims.activeTenantId() != null) {
            MDC.put(RequestIdMdcFilter.MDC_TENANT_ID, claims.activeTenantId());
        }

        try {
            if (claims.activeTenantId() != null) {
                runWithTenantBound(claims.activeTenantId(), request, response, chain);
            } else {
                chain.doFilter(request, response);
            }
        } finally {
            SecurityContextHolder.clearContext();
            MDC.remove(RequestIdMdcFilter.MDC_USER_ID);
            MDC.remove(RequestIdMdcFilter.MDC_TENANT_ID);
        }
    }

    /**
     * Wraps {@code chain.doFilter} in a tenant-bound scope. Any checked
     * exception thrown by the chain is unwrapped and rethrown with its
     * original type so Spring's exception resolvers see the real cause.
     */
    private static void runWithTenantBound(String tid,
                                           HttpServletRequest request,
                                           HttpServletResponse response,
                                           FilterChain chain) throws ServletException, IOException {
        try {
            TenantContext.callIn(tid, () -> {
                chain.doFilter(request, response);
                return null;
            });
        } catch (RuntimeException e) {
            throw e;
        } catch (Exception e) {
            if (e instanceof IOException io) throw io;
            if (e instanceof ServletException se) throw se;
            throw new ServletException(e);
        }
    }
}
