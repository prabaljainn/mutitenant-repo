package com.orochiverse.platform.testsupport;

import com.orochiverse.platform.common.security.jwt.AccessTokenIssuer;
import com.orochiverse.platform.iam.users.User;

/**
 * Test-only token minting that bypasses the {@code POST /api/auth/login}
 * round-trip. Useful when the test wants to exercise an authenticated
 * endpoint and doesn't care how the token was acquired.
 *
 * <h2>Why bypass /login?</h2>
 * <ul>
 *   <li><b>Speed.</b> BCrypt (cost 12) verification adds ~250&nbsp;ms per
 *       login. A test that sets up four users (owner / admin / editor /
 *       cross-tenant owner) used to spend ≥1&nbsp;second just hashing.
 *       Minting tokens directly drops that to a handful of milliseconds.</li>
 *   <li><b>Isolation.</b> Login goes through the full filter chain, the
 *       rate limiter, the audit writer, the metrics. A test that exercises
 *       {@code /admin/api/operators} doesn't want noise from those other
 *       systems.</li>
 *   <li><b>Negative tests.</b> Crafting tokens with stale {@code tv} or
 *       wrong {@code tid} is trivial here; impossible through {@code /login}.</li>
 * </ul>
 *
 * <h2>What this does NOT replace</h2>
 * Tests of the {@code /login} endpoint itself, of the rate limiter, or
 * of any error path that depends on the credential round-trip — those
 * still need to call {@code /login} for real.
 */
public final class JwtTestSupport {

    private JwtTestSupport() {}

    /**
     * Mint a token whose claims match {@code user} as it stands in the
     * repository (kind / role / tv). Operators get no {@code tid}; tenant
     * users get their owning {@code tid}.
     */
    public static String token(AccessTokenIssuer issuer, User user) {
        return tokenWithTid(issuer, user, naturalTid(user));
    }

    /**
     * Mint a token with an explicit {@code tid} override. For operators
     * this is the {@code switch-tenant} path (operator + active tenant);
     * for tenant users it's almost always wrong (their tenant is fixed)
     * and the caller is on the hook for the consequences.
     */
    public static String tokenWithTid(AccessTokenIssuer issuer, User user, String tenantId) {
        return issuer.issue(
                user.id(),
                user.email(),
                user.kind(),
                user.operatorRole(),
                tenantId,
                user.tenantRole(),
                user.tokenVersion()).token();
    }

    /**
     * Mint a token with an explicit {@code tv} that may not match the
     * user's current value — the way to construct tokens that the
     * {@code TokenVersionLookup} check should reject.
     */
    public static String tokenWithTokenVersion(AccessTokenIssuer issuer, User user, int tokenVersion) {
        return issuer.issue(
                user.id(),
                user.email(),
                user.kind(),
                user.operatorRole(),
                naturalTid(user),
                user.tenantRole(),
                tokenVersion).token();
    }

    private static String naturalTid(User user) {
        return switch (user.kind()) {
            case OPERATOR -> null;
            case TENANT_USER -> user.tenantId();
        };
    }
}
