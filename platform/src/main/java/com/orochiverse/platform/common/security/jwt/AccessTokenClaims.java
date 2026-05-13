package com.orochiverse.platform.common.security.jwt;

import java.time.Instant;

import com.orochiverse.platform.common.security.principals.OperatorRole;
import com.orochiverse.platform.common.security.principals.TenantRole;
import com.orochiverse.platform.common.security.principals.UserKind;

/**
 * Strongly-typed view of the access-token payload defined in §5.1 of the M1
 * design spec.
 *
 * <p>Field meaning per the spec:
 * <ul>
 *   <li>{@code issuer} → {@code iss} — the IAM hostname; verifiers reject
 *       tokens not issued by us.</li>
 *   <li>{@code userId} → {@code sub} — the {@code _id} of the user document.</li>
 *   <li>{@code email} — denormalized for display / logging without a DB hit.</li>
 *   <li>{@code kind} → {@code kind} — operator vs tenant user.</li>
 *   <li>{@code operatorRole} → {@code opRole} — non-null only for operators.</li>
 *   <li>{@code activeTenantId} → {@code tid} — the tenant the request will
 *       route to. For operators this is the tenant they {@code switch-tenant}'d
 *       into; for tenant users it equals their owning tenant.</li>
 *   <li>{@code tenantRole} → {@code tRole} — only meaningful when
 *       {@code activeTenantId} is set; for operators inside a switched
 *       tenant this stays null (their authority comes from
 *       {@code operatorRole}).</li>
 *   <li>{@code tokenVersion} → {@code tv} — bumped on password change /
 *       deactivation so we can invalidate every outstanding token in one DB
 *       write. The Phase 1.6 filter will compare this against the cached
 *       current version.</li>
 *   <li>{@code jti} — random UUID, used by Phase 1.10 audit + future
 *       per-token revocation.</li>
 * </ul>
 *
 * <p>This record is the single contract between {@link AccessTokenIssuer}
 * (which builds the JWT) and {@link AccessTokenVerifier} (which parses it).
 * Adding a claim is a one-spot change here plus matching put/get in the
 * issuer/verifier — keep them in lockstep.
 */
public record AccessTokenClaims(
        String issuer,
        String userId,
        String email,
        UserKind kind,
        OperatorRole operatorRole,
        String activeTenantId,
        TenantRole tenantRole,
        int tokenVersion,
        String jti,
        Instant issuedAt,
        Instant expiresAt) {

    public AccessTokenClaims {
        if (issuer == null || issuer.isBlank()) {
            throw new IllegalArgumentException("issuer is required");
        }
        if (userId == null || userId.isBlank()) {
            throw new IllegalArgumentException("userId is required");
        }
        if (email == null || email.isBlank()) {
            throw new IllegalArgumentException("email is required");
        }
        if (kind == null) {
            throw new IllegalArgumentException("kind is required");
        }
        if (kind == UserKind.OPERATOR && operatorRole == null) {
            throw new IllegalArgumentException("operatorRole is required when kind=OPERATOR");
        }
        if (kind == UserKind.TENANT_USER && (activeTenantId == null || tenantRole == null)) {
            throw new IllegalArgumentException("activeTenantId and tenantRole are required when kind=TENANT_USER");
        }
        if (jti == null || jti.isBlank()) {
            throw new IllegalArgumentException("jti is required");
        }
        if (issuedAt == null || expiresAt == null) {
            throw new IllegalArgumentException("issuedAt and expiresAt are required");
        }
        if (!expiresAt.isAfter(issuedAt)) {
            throw new IllegalArgumentException("expiresAt must be after issuedAt");
        }
    }
}
