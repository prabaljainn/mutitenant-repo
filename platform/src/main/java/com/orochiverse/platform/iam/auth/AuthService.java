package com.orochiverse.platform.iam.auth;

import java.time.Instant;
import java.util.Map;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Service;

import com.orochiverse.platform.common.audit.AuditAction;
import com.orochiverse.platform.common.audit.AuditEntry;
import com.orochiverse.platform.common.audit.AuditEntryRepository;
import com.orochiverse.platform.common.email.EmailProperties;
import com.orochiverse.platform.common.email.EmailService;
import com.orochiverse.platform.common.observability.AuthMetrics;
import com.orochiverse.platform.common.security.jwt.AccessTokenIssuer;
import com.orochiverse.platform.common.security.jwt.JwtProperties;
import com.orochiverse.platform.common.security.passwords.PasswordHashing;
import com.orochiverse.platform.common.security.principals.UserKind;
import com.orochiverse.platform.iam.auth.AuthDtos.SwitchTenantResponse;
import com.orochiverse.platform.iam.auth.AuthDtos.TokenResponse;
import com.orochiverse.platform.iam.operators.OperatorAssignmentRepository;
import com.orochiverse.platform.iam.tokens.InvalidTokenException;
import com.orochiverse.platform.iam.tokens.SingleUseToken;
import com.orochiverse.platform.iam.tokens.SingleUseTokenStore;
import com.orochiverse.platform.iam.tokens.TokenPurpose;
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
    private final SingleUseTokenStore singleUseTokens;
    private final AccessTokenIssuer issuer;
    private final PasswordHashing passwords;
    private final AuditEntryRepository audit;
    private final EmailService email;
    private final EmailProperties emailProps;
    private final LoginRateLimiter rateLimiter;
    private final org.springframework.beans.factory.ObjectProvider<
            com.orochiverse.platform.common.security.auth.TokenVersionLookup> tvResolver;
    private final AuthMetrics metrics;
    private final long accessTokenTtlSeconds;

    public AuthService(UserRepository users,
                       OperatorAssignmentRepository assignments,
                       RefreshTokenStore refreshTokens,
                       SingleUseTokenStore singleUseTokens,
                       AccessTokenIssuer issuer,
                       PasswordHashing passwords,
                       AuditEntryRepository audit,
                       EmailService email,
                       EmailProperties emailProps,
                       LoginRateLimiter rateLimiter,
                       org.springframework.beans.factory.ObjectProvider<
                               com.orochiverse.platform.common.security.auth.TokenVersionLookup> tvResolver,
                       AuthMetrics metrics,
                       JwtProperties jwtProperties) {
        this.users = users;
        this.assignments = assignments;
        this.refreshTokens = refreshTokens;
        this.singleUseTokens = singleUseTokens;
        this.issuer = issuer;
        this.passwords = passwords;
        this.audit = audit;
        this.email = email;
        this.emailProps = emailProps;
        this.rateLimiter = rateLimiter;
        this.tvResolver = tvResolver;
        this.metrics = metrics;
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
    public TokenResponse login(String email, String rawPassword, String ip, String userAgent) {
        // Throttle BEFORE doing any DB / hashing work — denies attackers
        // the per-attempt CPU + I/O cost in addition to the auth signal.
        try {
            rateLimiter.check(email, ip);
        } catch (RateLimitExceededException e) {
            metrics.loginRateLimited();
            throw e;
        }

        User user = users.findByEmailIgnoreCase(email).orElse(null);

        if (user == null || !passwords.matches(rawPassword, user.passwordHash())) {
            rateLimiter.recordFailure(email, ip);
            recordLoginFailure(email);
            metrics.loginFailure();
            throw new InvalidCredentialsException("invalid email or password");
        }
        if (user.status() != UserStatus.ACTIVE) {
            rateLimiter.recordFailure(email, ip);
            recordLoginFailure(email);
            metrics.loginFailure();
            throw new InvalidCredentialsException("invalid email or password");
        }

        rateLimiter.recordSuccess(email, ip);

        var access = issueAccessTokenForUser(user, /*tidOverride*/ null);
        var refresh = refreshTokens.issue(user.id(), userAgent, ip, /*firstSeenAt*/ null);

        audit.save(AuditEntry.of(AuditAction.LOGIN_SUCCESS, user.id()));
        metrics.loginSuccess();
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

    public TokenResponse refresh(String refreshToken, String ip, String userAgent) {
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

        // Carry the original session's firstSeenAt forward so the Account
        // page can show "signed in 3 days ago" instead of "1 second ago"
        // every time the SPA rotates. UA / IP take the freshest values —
        // matches the Google "last seen here" behaviour.
        var access = issueAccessTokenForUser(user, /*tidOverride*/ null);
        String nextUa = userAgent != null ? userAgent : consumed.userAgent();
        String nextIp = ip != null ? ip : consumed.ip();
        var freshRefresh = refreshTokens.issue(user.id(), nextUa, nextIp, consumed.firstSeenAt());

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

        audit.save(AuditEntry.of(AuditAction.TENANT_SWITCHED, operatorUserId, tenantId,
                java.util.Map.of("tenantId", tenantId)));

        return SwitchTenantResponse.bearer(access, accessTokenTtlSeconds);
    }

    // ─────────────────────────────────────────────────────────────────────
    // Phase 1.9: forgot password / reset password / accept invite
    // ─────────────────────────────────────────────────────────────────────

    /**
     * Always returns to the caller without revealing whether the email
     * exists. If a real ACTIVE user matches we issue a single-use token
     * and email a reset link; otherwise we silently no-op. Audit row only
     * on the matching path so log analysis can distinguish the two.
     */
    public void requestPasswordReset(String email) {
        User user = users.findByEmailIgnoreCase(email).orElse(null);
        if (user == null || user.status() != UserStatus.ACTIVE) {
            log.info("password reset requested for unknown/non-active email — silent no-op");
            return;
        }

        SingleUseToken token = singleUseTokens.issue(user.id(), TokenPurpose.PASSWORD_RESET);
        sendResetEmail(user, token);
        audit.save(AuditEntry.of(AuditAction.PASSWORD_RESET_REQUESTED, user.id(),
                Map.of("email", user.email())));
        metrics.passwordResetRequested();
    }

    /**
     * Validates the token, hashes the new password, and revokes every
     * outstanding refresh token for that user (otherwise a stolen
     * refresh would survive the password rotation). Audit
     * {@code PASSWORD_RESET_COMPLETED}. Does NOT log the user in — the
     * client navigates back to {@code /login} with the new password.
     */
    public void resetPassword(String token, String newPassword) {
        SingleUseToken consumed = singleUseTokens.consume(token, TokenPurpose.PASSWORD_RESET);
        User user = users.findById(consumed.userId())
                .orElseThrow(() -> new InvalidTokenException("user no longer exists"));
        if (user.status() != UserStatus.ACTIVE) {
            // Suspended/deleted between issue and consume — invalidate the
            // attempt rather than silently re-activating.
            throw new InvalidTokenException("user is not active");
        }

        users.save(withNewPassword(user, newPassword));
        refreshTokens.revokeAllForUser(user.id());
        // Bump invalidates cached tv so existing access tokens are
        // rejected on their next request (within the cache window).
        var resolver = tvResolver.getIfAvailable();
        if (resolver != null) {
            resolver.invalidate(user.id());
        }

        audit.save(AuditEntry.of(AuditAction.PASSWORD_RESET_COMPLETED, user.id(),
                Map.of("email", user.email())));
        metrics.passwordResetCompleted();
        log.info("password reset completed user={}", user.id());
    }

    /**
     * One-step onboarding: validates the invite token, sets the password,
     * flips status from INVITED to ACTIVE, and returns a fresh access +
     * refresh pair so the user lands logged in. Audit
     * {@code PASSWORD_CHANGED} (no dedicated USER_ACTIVATED action yet
     * — the OPERATOR_INVITED / TENANT_USER_INVITED entry already records
     * the invite).
     */
    public TokenResponse acceptInvite(String token, String newPassword, String ip, String userAgent) {
        SingleUseToken consumed = singleUseTokens.consume(token, TokenPurpose.INVITE_ACCEPT);
        User user = users.findById(consumed.userId())
                .orElseThrow(() -> new InvalidTokenException("user no longer exists"));
        if (user.status() != UserStatus.INVITED) {
            // Already accepted (and somehow the token survived), or
            // suspended/deleted. Either way: don't apply.
            throw new InvalidTokenException("invite is no longer valid");
        }

        User activated = withPasswordAndStatus(user, newPassword, UserStatus.ACTIVE);
        users.save(activated);

        audit.save(AuditEntry.of(AuditAction.PASSWORD_CHANGED, user.id(),
                Map.of("email", user.email(), "via", "invite_accept")));
        log.info("invite accepted user={} kind={}", user.id(), user.kind());

        // Auto-login: issue access + refresh just like POST /login would.
        var access = issueAccessTokenForUser(activated, /*tidOverride*/ null);
        var refresh = refreshTokens.issue(activated.id(), userAgent, ip, /*firstSeenAt*/ null);
        return TokenResponse.bearer(access, refresh.token(), accessTokenTtlSeconds);
    }

    private void sendResetEmail(User user, SingleUseToken token) {
        String resetUrl = emailProps.baseUrl() + "/reset-password?token=" + token.token();
        email.send(user.email(),
                "Reset your Orochiverse password",
                "password-reset",
                Map.of(
                        "firstName", user.firstName(),
                        "email", user.email(),
                        "resetUrl", resetUrl,
                        "expiresAt", token.expiresAt().toString()));
    }

    /** Returns a copy of {@code user} with a freshly-hashed password. */
    private User withNewPassword(User user, String rawPassword) {
        return new User(
                user.id(), user.email(), passwords.hash(rawPassword),
                user.firstName(), user.lastName(),
                user.status(), user.kind(), user.operatorRole(),
                user.tenantId(), user.tenantRole(),
                user.tokenVersion() + 1, // bump tv so older access tokens become "stale" (tv check Phase 1.10)
                user.lastLoginAt(), user.createdAt(), Instant.now());
    }

    /** Like {@link #withNewPassword} but also flips status. Used by {@link #acceptInvite}. */
    private User withPasswordAndStatus(User user, String rawPassword, UserStatus status) {
        return new User(
                user.id(), user.email(), passwords.hash(rawPassword),
                user.firstName(), user.lastName(),
                status, user.kind(), user.operatorRole(),
                user.tenantId(), user.tenantRole(),
                user.tokenVersion(),
                user.lastLoginAt(), user.createdAt(), Instant.now());
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
