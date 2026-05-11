package com.orochiverse.platform.iam.auth;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Service;

import com.orochiverse.platform.common.audit.AuditAction;
import com.orochiverse.platform.common.audit.AuditEntry;
import com.orochiverse.platform.common.audit.AuditEntryRepository;
import com.orochiverse.platform.common.security.jwt.AccessTokenIssuer;
import com.orochiverse.platform.common.security.jwt.JwtProperties;
import com.orochiverse.platform.common.security.passwords.PasswordHashing;
import com.orochiverse.platform.common.security.principals.UserKind;
import com.orochiverse.platform.iam.auth.AuthDtos.SwitchTenantResponse;
import com.orochiverse.platform.iam.auth.AuthDtos.TokenResponse;
import com.orochiverse.platform.iam.operators.OperatorAssignmentRepository;
import com.orochiverse.platform.iam.users.User;
import com.orochiverse.platform.iam.users.UserRepository;
import com.orochiverse.platform.iam.users.UserStatus;

/**
 * The four flows behind {@code /api/auth/*}: login, refresh, logout,
 * switch-tenant. All persistence sits behind injected repositories so the
 * service is straight to unit-test.
 *
 * <h2>Token shapes recap</h2>
 * <ul>
 *   <li><b>Access token</b> — signed JWT, 15-min TTL, claims per spec §5.1.
 *       Carried as {@code Authorization: Bearer ...}. Never stored
 *       server-side.</li>
 *   <li><b>Refresh token</b> — opaque random 256-bit string. Lives in
 *       {@link RefreshTokenStore}. Single-shot: rotated on every
 *       {@link #refresh}.</li>
 * </ul>
 *
 * <h2>Audit</h2>
 * Login attempts (success + failure) and tenant switches are written to
 * {@link AuditEntryRepository}. Token refresh and logout are intentionally
 * not audited — they're high-frequency and low-signal; the underlying
 * login event is the audit-worthy one.
 */
@Service
@ConditionalOnProperty(prefix = "spring.data.mongodb", name = "uri")
public class AuthService {

    private static final Logger log = LoggerFactory.getLogger(AuthService.class);

    private final UserRepository users;
    private final OperatorAssignmentRepository assignments;
    private final RefreshTokenStore refreshTokens;
    private final AccessTokenIssuer issuer;
    private final PasswordHashing passwords;
    private final AuditEntryRepository audit;
    private final long accessTokenTtlSeconds;

    public AuthService(UserRepository users,
                       OperatorAssignmentRepository assignments,
                       RefreshTokenStore refreshTokens,
                       AccessTokenIssuer issuer,
                       PasswordHashing passwords,
                       AuditEntryRepository audit,
                       JwtProperties jwtProperties) {
        this.users = users;
        this.assignments = assignments;
        this.refreshTokens = refreshTokens;
        this.issuer = issuer;
        this.passwords = passwords;
        this.audit = audit;
        this.accessTokenTtlSeconds = jwtProperties.accessTokenTtl().toSeconds();
    }

    // ─────────────────────────────────────────────────────────────────────
    // Login
    // ─────────────────────────────────────────────────────────────────────

    /**
     * Verifies email + password, audits the attempt either way, and returns
     * a fresh access + refresh token pair on success. All failure modes
     * (unknown email, bad password, non-ACTIVE status) surface as the
     * single {@link InvalidCredentialsException} so the response can't be
     * used to enumerate accounts.
     */
    public TokenResponse login(String email, String rawPassword) {
        User user = users.findByEmailIgnoreCase(email).orElse(null);

        if (user == null || !passwords.matches(rawPassword, user.passwordHash())) {
            recordLoginFailure(email);
            throw new InvalidCredentialsException("invalid email or password");
        }
        if (user.status() != UserStatus.ACTIVE) {
            recordLoginFailure(email);
            throw new InvalidCredentialsException("invalid email or password");
        }

        var access = issueAccessTokenForUser(user, /*tidOverride*/ null);
        var refresh = refreshTokens.issue(user.id());

        audit.save(AuditEntry.of(AuditAction.LOGIN_SUCCESS, user.id()));
        log.info("login ok user={} kind={}", user.id(), user.kind());

        return TokenResponse.bearer(access, refresh.token(), accessTokenTtlSeconds);
    }

    private void recordLoginFailure(String email) {
        // We don't know the userId for unknown emails — the audit row uses
        // a synthetic actor so the failure is still queryable. Keep the
        // email in a separate metadata field rather than the actor so we
        // don't spam actorUserId with arbitrary input.
        audit.save(AuditEntry.of(AuditAction.LOGIN_FAILURE, "unknown",
                java.util.Map.of("email", email)));
        log.info("login failed email={}", email);
    }

    // ─────────────────────────────────────────────────────────────────────
    // Refresh (rotation on use)
    // ─────────────────────────────────────────────────────────────────────

    public TokenResponse refresh(String refreshToken) {
        RefreshToken consumed = refreshTokens.consume(refreshToken)
                .orElseThrow(() -> new InvalidRefreshTokenException("refresh token not recognized"));

        User user = users.findById(consumed.userId())
                .orElseThrow(() -> new InvalidRefreshTokenException("user no longer exists"));

        if (user.status() != UserStatus.ACTIVE) {
            // Don't issue a new pair — the user is suspended/deleted. The
            // old refresh was already removed by consume(), so this also
            // ends the session.
            throw new InvalidRefreshTokenException("user is not active");
        }

        var access = issueAccessTokenForUser(user, /*tidOverride*/ null);
        var freshRefresh = refreshTokens.issue(user.id());

        return TokenResponse.bearer(access, freshRefresh.token(), accessTokenTtlSeconds);
    }

    // ─────────────────────────────────────────────────────────────────────
    // Logout
    // ─────────────────────────────────────────────────────────────────────

    /**
     * Best-effort: revokes the refresh token if supplied. Idempotent. The
     * access token can't be invalidated server-side without a denylist —
     * it'll naturally expire within {@code accessTokenTtl}.
     */
    public void logout(String refreshToken) {
        if (refreshToken == null || refreshToken.isBlank()) {
            return;
        }
        refreshTokens.revoke(refreshToken);
    }

    // ─────────────────────────────────────────────────────────────────────
    // Switch tenant (operator only)
    // ─────────────────────────────────────────────────────────────────────

    /**
     * Issues a new access token whose {@code tid} claim is the requested
     * tenant. Only operators with an active assignment for that tenant
     * may switch; tenant users are bound to their owning tenant for the
     * lifetime of the session and don't need to switch.
     *
     * <p>Refresh token is unchanged — same session, just a different
     * active tenant.
     */
    public SwitchTenantResponse switchTenant(String operatorUserId, String tenantId) {
        User user = users.findById(operatorUserId)
                .orElseThrow(() -> new InvalidCredentialsException("operator no longer exists"));

        if (user.kind() != UserKind.OPERATOR) {
            throw new OperatorNotAssignedException("only operators can switch tenants");
        }
        if (user.status() != UserStatus.ACTIVE) {
            throw new InvalidCredentialsException("operator is not active");
        }
        if (!assignments.existsByOperatorUserIdAndTenantId(operatorUserId, tenantId)) {
            throw new OperatorNotAssignedException(
                    "operator " + operatorUserId + " is not assigned to tenant " + tenantId);
        }

        var access = issueAccessTokenForUser(user, tenantId);

        audit.save(AuditEntry.of(AuditAction.TENANT_SWITCHED, operatorUserId,
                java.util.Map.of("tenantId", tenantId)));

        return SwitchTenantResponse.bearer(access, accessTokenTtlSeconds);
    }

    // ─────────────────────────────────────────────────────────────────────
    // Helpers
    // ─────────────────────────────────────────────────────────────────────

    /**
     * Build the appropriate access token for {@code user}. {@code tidOverride}
     * is the explicit tenant for {@code switch-tenant}; pass {@code null}
     * to use the user's natural tenant binding (own tenant for tenant
     * users; no tid for operators).
     */
    private String issueAccessTokenForUser(User user, String tidOverride) {
        String tid = switch (user.kind()) {
            case OPERATOR -> tidOverride; // null means "no active tenant"
            case TENANT_USER -> user.tenantId();
        };
        return issuer.issue(
                user.id(),
                user.email(),
                user.kind(),
                user.operatorRole(),
                tid,
                user.tenantRole(),
                user.tokenVersion()).token();
    }
}
