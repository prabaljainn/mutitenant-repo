package com.orochiverse.platform.iam.auth;

import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.web.server.ResponseStatusException;

import com.orochiverse.platform.common.audit.AuditAction;
import com.orochiverse.platform.common.audit.AuditEntry;
import com.orochiverse.platform.common.audit.AuditEntryRepository;
import com.orochiverse.platform.common.security.auth.TokenVersionLookup;
import com.orochiverse.platform.common.security.jwt.AccessTokenIssuer;
import com.orochiverse.platform.common.security.jwt.JwtProperties;
import com.orochiverse.platform.common.security.passwords.PasswordHashing;
import com.orochiverse.platform.iam.auth.AuthDtos.TokenResponse;
import com.orochiverse.platform.iam.auth.MeSelfServiceDtos.ProfileResponse;
import com.orochiverse.platform.iam.auth.MeSelfServiceDtos.SessionResponse;
import com.orochiverse.platform.iam.users.User;
import com.orochiverse.platform.iam.users.UserRepository;
import com.orochiverse.platform.iam.users.UserStatus;

/**
 * Operator + tenant-user self-service: edit own profile, change own
 * password, list / revoke own active refresh-token sessions.
 *
 * <h2>Password change side-effects</h2>
 * <ol>
 *   <li>Verify {@code currentPassword} — wrong → 401 via
 *       {@link InvalidCredentialsException} (mirrors the login envelope so
 *       account state isn't disclosed).</li>
 *   <li>Reject "new password equals current" — 422.</li>
 *   <li>Bump {@code tokenVersion} so in-flight access tokens are rejected
 *       on their next request (within the cache window).</li>
 *   <li>Revoke every outstanding refresh token (kills other devices / tabs).</li>
 *   <li>Issue a fresh access + refresh pair so the calling tab stays
 *       logged in.</li>
 *   <li>Audit {@link AuditAction#PASSWORD_CHANGED} with
 *       {@code metadata.via=self_service}.</li>
 * </ol>
 *
 * <h2>Session revoke</h2>
 * Idempotent: revoking an unknown session id is a no-op. The caller knows
 * which sessions exist via {@link #listSessions(String)}; we don't want a
 * 404 on a deletion that already happened.
 */
@Service
@ConditionalOnProperty(prefix = "spring.data.mongodb", name = "uri")
public class MeSelfService {

    private static final Logger log = LoggerFactory.getLogger(MeSelfService.class);

    private final UserRepository users;
    private final RefreshTokenStore refreshTokens;
    private final PasswordHashing passwords;
    private final AccessTokenIssuer issuer;
    private final AuditEntryRepository audit;
    private final long accessTokenTtlSeconds;
    private final org.springframework.beans.factory.ObjectProvider<TokenVersionLookup> tvResolver;

    public MeSelfService(UserRepository users,
                         RefreshTokenStore refreshTokens,
                         PasswordHashing passwords,
                         AccessTokenIssuer issuer,
                         AuditEntryRepository audit,
                         JwtProperties jwtProperties,
                         org.springframework.beans.factory.ObjectProvider<TokenVersionLookup> tvResolver) {
        this.users = users;
        this.refreshTokens = refreshTokens;
        this.passwords = passwords;
        this.issuer = issuer;
        this.audit = audit;
        this.accessTokenTtlSeconds = jwtProperties.accessTokenTtl().toSeconds();
        this.tvResolver = tvResolver;
    }

    public ProfileResponse getProfile(String userId) {
        return ProfileResponse.from(loadActiveOrThrow(userId));
    }

    public ProfileResponse updateProfile(String userId, String firstName, String lastName) {
        User existing = loadActiveOrThrow(userId);

        String fn = firstName == null ? existing.firstName() : firstName;
        String ln = lastName == null ? existing.lastName() : lastName;
        if (fn.equals(existing.firstName()) && ln.equals(existing.lastName())) {
            // No-op — don't bump updatedAt or audit a non-change.
            return ProfileResponse.from(existing);
        }

        var updated = new User(
                existing.id(), existing.email(), existing.passwordHash(),
                fn, ln,
                existing.status(), existing.kind(), existing.operatorRole(),
                existing.tenantId(), existing.tenantRole(),
                existing.tokenVersion(), existing.lastLoginAt(),
                existing.createdAt(), Instant.now());
        var saved = users.save(updated);

        var changes = new LinkedHashMap<String, Object>();
        if (firstName != null && !firstName.equals(existing.firstName())) {
            changes.put("firstName", firstName);
        }
        if (lastName != null && !lastName.equals(existing.lastName())) {
            changes.put("lastName", lastName);
        }
        audit.save(AuditEntry.of(AuditAction.PROFILE_UPDATED, userId,
                Map.of("userId", userId, "changes", changes)));
        log.info("profile updated user={}", userId);
        return ProfileResponse.from(saved);
    }

    public TokenResponse changePassword(String userId, String currentPassword, String newPassword) {
        User existing = loadActiveOrThrow(userId);

        if (!passwords.matches(currentPassword, existing.passwordHash())) {
            // Reuse the login envelope (401, invalid_credentials) so a bad
            // current password can't be probed differently from a stolen
            // session attempt.
            throw new InvalidCredentialsException("current password is incorrect");
        }
        if (passwords.matches(newPassword, existing.passwordHash())) {
            throw new ResponseStatusException(HttpStatus.UNPROCESSABLE_ENTITY,
                    "new password must differ from current");
        }

        var updated = new User(
                existing.id(), existing.email(), passwords.hash(newPassword),
                existing.firstName(), existing.lastName(),
                existing.status(), existing.kind(), existing.operatorRole(),
                existing.tenantId(), existing.tenantRole(),
                existing.tokenVersion() + 1, existing.lastLoginAt(),
                existing.createdAt(), Instant.now());
        var saved = users.save(updated);

        // Kill every outstanding refresh token (including this tab's), then
        // mint a fresh pair to hand back so the caller stays logged in on
        // this device. Other devices are forcibly logged out — the whole
        // point of "change password" as a security action.
        refreshTokens.revokeAllForUser(userId);
        var resolver = tvResolver.getIfAvailable();
        if (resolver != null) {
            resolver.invalidate(userId);
        }

        String access = issueAccessTokenForUser(saved);
        var refresh = refreshTokens.issue(userId);

        audit.save(AuditEntry.of(AuditAction.PASSWORD_CHANGED, userId,
                Map.of("userId", userId, "via", "self_service")));
        log.info("password changed via self-service user={}", userId);

        return TokenResponse.bearer(access, refresh.token(), accessTokenTtlSeconds);
    }

    public List<SessionResponse> listSessions(String userId) {
        return refreshTokens.listForUser(userId).stream()
                .map(s -> new SessionResponse(s.id(), s.issuedAt(), s.expiresAt()))
                .toList();
    }

    /**
     * Idempotent revoke. {@code revokeByIdForUser} returns false for an
     * unknown id; we still audit + return 204 either way so the caller
     * can't observe whether the id existed (a tiny side-channel against
     * id enumeration).
     */
    public void revokeSession(String userId, String sessionId) {
        boolean revoked = refreshTokens.revokeByIdForUser(sessionId, userId);
        if (revoked) {
            audit.save(AuditEntry.of(AuditAction.TOKEN_REVOKED, userId,
                    Map.of("userId", userId, "sessionId", sessionId, "via", "self_service")));
            log.info("session revoked user={} sessionId={}", userId, sessionId);
        }
    }

    private User loadActiveOrThrow(String userId) {
        User u = users.findById(userId).orElseThrow(() ->
                // Authenticated user with no corresponding row → broken
                // invariant. Surface as 401 not 500 so the client just
                // logs them out cleanly.
                new InvalidCredentialsException("user " + userId + " no longer exists"));
        if (u.status() != UserStatus.ACTIVE) {
            throw new InvalidCredentialsException("user is not active");
        }
        return u;
    }

    private String issueAccessTokenForUser(User user) {
        // Operators get null tid by default (they switch-tenant explicitly);
        // tenant users are pinned to their owning tenant.
        String tid = switch (user.kind()) {
            case OPERATOR -> null;
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
