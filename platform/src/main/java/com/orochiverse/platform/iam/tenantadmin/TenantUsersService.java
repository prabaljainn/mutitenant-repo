package com.orochiverse.platform.iam.tenantadmin;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Service;

import com.orochiverse.platform.common.audit.AuditAction;
import com.orochiverse.platform.common.audit.AuditEntry;
import com.orochiverse.platform.common.audit.AuditEntryRepository;
import com.orochiverse.platform.common.email.EmailProperties;
import com.orochiverse.platform.common.email.EmailService;
import com.orochiverse.platform.common.observability.AuthMetrics;
import com.orochiverse.platform.common.security.principals.TenantRole;
import com.orochiverse.platform.common.security.principals.UserKind;
import com.orochiverse.platform.common.tenant.TenantContext;
import com.orochiverse.platform.iam.admin.common.AdminExceptions.ConflictException;
import com.orochiverse.platform.iam.admin.common.AdminExceptions.NotFoundException;
import com.orochiverse.platform.iam.admin.common.AdminExceptions.UnprocessableException;
import com.orochiverse.platform.iam.auth.RefreshTokenStore;
import com.orochiverse.platform.iam.tenantadmin.TenantSelfDtos.InviteTenantUserRequest;
import com.orochiverse.platform.iam.tenantadmin.TenantSelfDtos.TenantUserResponse;
import com.orochiverse.platform.iam.tenantadmin.TenantSelfDtos.UpdateTenantUserRequest;
import com.orochiverse.platform.iam.tenants.Tenant;
import com.orochiverse.platform.iam.tenants.TenantRepository;
import com.orochiverse.platform.iam.tokens.SingleUseToken;
import com.orochiverse.platform.iam.tokens.SingleUseTokenStore;
import com.orochiverse.platform.iam.tokens.TokenPurpose;
import com.orochiverse.platform.iam.users.User;
import com.orochiverse.platform.iam.users.UserRepository;
import com.orochiverse.platform.iam.users.UserStatus;

/**
 * Tenant-user lifecycle for tenant admins. Every operation is scoped to
 * {@link TenantContext#requireCurrent()} — a tenant admin literally cannot
 * see or touch users in another tenant, even if they forge an id in the URL.
 *
 * <h2>Tenancy isolation</h2>
 * The {@link com.orochiverse.platform.common.security.auth.JwtAuthenticationFilter}
 * binds {@code TenantContext} from the verified {@code tid} JWT claim
 * before reaching this service. Every read filters by
 * {@code tenantId == requireCurrent()}; every write loads the target,
 * confirms its tenant matches, and only then mutates. Cross-tenant
 * traversal returns {@code 404 not_found} — same as a missing user — so
 * we don't even leak existence across tenants.
 *
 * <h2>Ownership</h2>
 * <ul>
 *   <li>Ownership is a {@code Tenant.ownerUserId} field, not a role. There
 *       are exactly two roles ({@code ADMIN}, {@code MEMBER}).</li>
 *   <li>The first {@code ADMIN} invited to a tenant with no owner is
 *       auto-promoted to owner.</li>
 *   <li>The user pointed at by {@code ownerUserId} cannot be demoted to
 *       {@code MEMBER}, suspended, or deleted via this surface — transfer
 *       ownership first (separate flow, not yet implemented).</li>
 * </ul>
 *
 * <h2>Audit</h2>
 * Mutations write {@link AuditAction#TENANT_USER_INVITED},
 * {@code TENANT_USER_ROLE_CHANGED}, {@code TENANT_USER_SUSPENDED},
 * {@code TENANT_USER_DELETED}. The {@code tenantId} field is set so
 * {@code GET /admin/api/audit?tenantId=…} can filter to a single
 * tenant's activity.
 */
@Service
@ConditionalOnProperty(prefix = "spring.data.mongodb", name = "uri")
public class TenantUsersService {

    private static final Logger log = LoggerFactory.getLogger(TenantUsersService.class);

    private final UserRepository users;
    private final TenantRepository tenants;
    private final RefreshTokenStore refreshTokens;
    private final SingleUseTokenStore singleUseTokens;
    private final AuditEntryRepository audit;
    private final EmailService email;
    private final EmailProperties emailProps;
    private final AuthMetrics metrics;
    private final org.springframework.beans.factory.ObjectProvider<
            com.orochiverse.platform.common.security.auth.TokenVersionLookup> tvResolver;

    public TenantUsersService(UserRepository users,
                              TenantRepository tenants,
                              RefreshTokenStore refreshTokens,
                              SingleUseTokenStore singleUseTokens,
                              AuditEntryRepository audit,
                              EmailService email,
                              EmailProperties emailProps,
                              AuthMetrics metrics,
                              org.springframework.beans.factory.ObjectProvider<
                                      com.orochiverse.platform.common.security.auth.TokenVersionLookup> tvResolver) {
        this.users = users;
        this.tenants = tenants;
        this.refreshTokens = refreshTokens;
        this.singleUseTokens = singleUseTokens;
        this.audit = audit;
        this.email = email;
        this.emailProps = emailProps;
        this.metrics = metrics;
        this.tvResolver = tvResolver;
    }

    private void invalidateTvCache(String userId) {
        var resolver = tvResolver.getIfAvailable();
        if (resolver != null) {
            resolver.invalidate(userId);
        }
    }

    // ─────────────────────────────────────────────────────────────────────
    // Invite
    // ─────────────────────────────────────────────────────────────────────

    public TenantUserResponse invite(InviteTenantUserRequest req, String actorUserId) {
        String tenantId = TenantContext.requireCurrent();

        if (users.existsByEmailIgnoreCase(req.email())) {
            throw new ConflictException("user with email " + req.email() + " already exists");
        }

        String id = "tuser-" + UUID.randomUUID().toString().substring(0, 8);
        Instant now = Instant.now();
        var invited = new User(
                id, req.email(), null,
                req.firstName(), req.lastName(),
                UserStatus.INVITED, UserKind.TENANT_USER, null,
                tenantId, req.role(), 0, null, now, now);

        User saved;
        try {
            saved = users.save(invited);
        } catch (DuplicateKeyException e) {
            throw new ConflictException("user with email " + req.email() + " already exists");
        }

        // Auto-promote: the first ADMIN invited to an ownerless tenant
        // becomes its owner. Subsequent ADMINs join as plain admins. The
        // owner can later be reassigned via a dedicated transfer flow.
        if (req.role() == TenantRole.ADMIN) {
            promoteToOwnerIfFirst(tenantId, saved.id());
        }

        SingleUseToken accept = singleUseTokens.issue(id, TokenPurpose.INVITE_ACCEPT);
        try {
            sendTenantUserInviteEmail(saved, tenantId, req.role().name(), accept);
        } catch (RuntimeException e) {
            log.warn("tenant user invite created but email failed id={} email={}: {}",
                    id, req.email(), e.getMessage());
        }

        audit.save(auditEntry(AuditAction.TENANT_USER_INVITED, actorUserId, tenantId,
                Map.of("tenantUserId", id, "email", req.email(), "role", req.role().name())));
        metrics.inviteTenantUser();
        log.info("tenant user invited tenant={} id={} email={} role={} actor={}",
                tenantId, id, req.email(), req.role(), actorUserId);
        return TenantUserResponse.from(saved);
    }

    private void promoteToOwnerIfFirst(String tenantId, String userId) {
        tenants.findByIdAndDeletedAtIsNull(tenantId).ifPresent(t -> {
            if (t.ownerUserId() == null) {
                tenants.save(t.withOwner(userId));
                log.info("auto-promoted first admin as tenant owner: tenant={} user={}",
                        tenantId, userId);
            }
        });
    }

    private void sendTenantUserInviteEmail(User user, String tenantId, String role,
                                           SingleUseToken accept) {
        String tenantName = tenants.findByIdAndDeletedAtIsNull(tenantId).map(Tenant::name).orElse(tenantId);
        String acceptUrl = emailProps.baseUrl() + "/accept-invite?token=" + accept.token();
        email.send(user.email(),
                "You're invited to " + tenantName + " on Orochiverse",
                "invite-tenant-user",
                Map.of(
                        "firstName", user.firstName(),
                        "tenantName", tenantName,
                        "role", role,
                        "acceptUrl", acceptUrl,
                        "expiresAt", accept.expiresAt().toString()));
    }

    /**
     * Re-send the invite email for a tenant user stuck in
     * {@link UserStatus#INVITED}. Issues a fresh accept token and revokes
     * any prior outstanding ones so the link from the original email
     * becomes inert. 422 if the user has already accepted.
     */
    public TenantUserResponse resendInvite(String id, String actorUserId) {
        String tenantId = TenantContext.requireCurrent();
        User existing = loadInCurrentTenantOrThrow(id);
        if (existing.status() != UserStatus.INVITED) {
            throw new UnprocessableException(
                    "tenant user " + id + " is not in INVITED status — no invite to resend");
        }

        singleUseTokens.revokeAllForUser(id);
        SingleUseToken accept = singleUseTokens.issue(id, TokenPurpose.INVITE_ACCEPT);
        try {
            sendTenantUserInviteEmail(existing, tenantId, existing.tenantRole().name(), accept);
        } catch (RuntimeException e) {
            log.warn("tenant user invite resend issued but email failed id={} email={}: {}",
                    id, existing.email(), e.getMessage());
        }

        audit.save(auditEntry(AuditAction.TENANT_USER_INVITE_RESENT, actorUserId, tenantId,
                Map.of("tenantUserId", id, "email", existing.email())));
        log.info("tenant user invite resent tenant={} id={} actor={}", tenantId, id, actorUserId);
        return TenantUserResponse.from(existing);
    }

    // ─────────────────────────────────────────────────────────────────────
    // Read
    // ─────────────────────────────────────────────────────────────────────

    public List<TenantUserResponse> list(UserStatus statusFilter) {
        String tenantId = TenantContext.requireCurrent();
        var status = statusFilter == null ? UserStatus.ACTIVE : statusFilter;
        return users.findAllByTenantIdAndStatus(tenantId, status).stream()
                .map(TenantUserResponse::from).toList();
    }

    public TenantUserResponse get(String id) {
        return TenantUserResponse.from(loadInCurrentTenantOrThrow(id));
    }

    // ─────────────────────────────────────────────────────────────────────
    // Update
    // ─────────────────────────────────────────────────────────────────────

    public TenantUserResponse update(String id, UpdateTenantUserRequest req, String actorUserId) {
        String tenantId = TenantContext.requireCurrent();
        User existing = loadInCurrentTenantOrThrow(id);

        if (req.status() == UserStatus.DELETED) {
            throw new UnprocessableException(
                    "use DELETE /api/tenant/users/{id} to soft-delete a tenant user");
        }

        // Owner-protection: the user pointed at by Tenant.ownerUserId
        // cannot be demoted to MEMBER, suspended, or otherwise taken out
        // of active-admin status via this endpoint. Transfer ownership
        // first (separate flow, not yet implemented).
        boolean isOwner = id.equals(currentOwnerId(tenantId));
        boolean wouldDemote = isOwner && req.role() != null && req.role() != TenantRole.ADMIN;
        boolean wouldDeactivate = isOwner && req.status() != null
                && req.status() != UserStatus.ACTIVE;
        if (wouldDemote || wouldDeactivate) {
            throw new UnprocessableException(
                    "cannot demote or deactivate the tenant owner — transfer ownership first");
        }

        String firstName = req.firstName() == null ? existing.firstName() : req.firstName();
        String lastName  = req.lastName()  == null ? existing.lastName()  : req.lastName();
        TenantRole role  = req.role()      == null ? existing.tenantRole() : req.role();
        UserStatus status = req.status()   == null ? existing.status()    : req.status();

        // Any change that affects what the user's existing JWT means —
        // role change OR leaving ACTIVE — bumps tokenVersion. Together
        // with TokenVersionLookup in JwtAuthenticationFilter, this revokes
        // in-flight access tokens immediately rather than waiting out the
        // 15-minute access TTL. Cosmetic edits do not bump tv.
        boolean roleChanged = req.role() != null && req.role() != existing.tenantRole();
        boolean leftActive  = req.status() != null && req.status() != existing.status()
                                                  && req.status() != UserStatus.ACTIVE;
        boolean privilegeChange = roleChanged || leftActive;
        int newTv = privilegeChange ? existing.tokenVersion() + 1 : existing.tokenVersion();

        var updated = new User(
                existing.id(), existing.email(), existing.passwordHash(),
                firstName, lastName,
                status, existing.kind(), null,
                existing.tenantId(), role,
                newTv, existing.lastLoginAt(),
                existing.createdAt(), Instant.now());
        var saved = users.save(updated);
        if (privilegeChange) {
            invalidateTvCache(id);
        }

        if (roleChanged) {
            audit.save(auditEntry(AuditAction.TENANT_USER_ROLE_CHANGED, actorUserId, tenantId,
                    Map.of("tenantUserId", id,
                            "from", existing.tenantRole().name(),
                            "to", req.role().name())));
        }
        if (req.status() != null && req.status() != existing.status()
                && req.status() == UserStatus.SUSPENDED) {
            refreshTokens.revokeAllForUser(id);
            singleUseTokens.revokeAllForUser(id);
            audit.save(auditEntry(AuditAction.TENANT_USER_SUSPENDED, actorUserId, tenantId,
                    Map.of("tenantUserId", id)));
        }
        return TenantUserResponse.from(saved);
    }

    // ─────────────────────────────────────────────────────────────────────
    // Soft-delete
    // ─────────────────────────────────────────────────────────────────────

    public void softDelete(String id, String actorUserId) {
        String tenantId = TenantContext.requireCurrent();
        if (id.equals(actorUserId)) {
            throw new UnprocessableException("tenant users cannot delete themselves");
        }
        User existing = loadInCurrentTenantOrThrow(id);

        if (id.equals(currentOwnerId(tenantId))) {
            throw new UnprocessableException(
                    "cannot delete the tenant owner — transfer ownership first");
        }

        // Scramble the email so the unique index slot is freed for a future
        // invite. The original lives on in the audit metadata below.
        String originalEmail = existing.email();
        var deleted = new User(
                existing.id(), User.deletedEmailMarker(existing.id()), existing.passwordHash(),
                existing.firstName(), existing.lastName(),
                UserStatus.DELETED, existing.kind(), null,
                existing.tenantId(), existing.tenantRole(),
                existing.tokenVersion() + 1, existing.lastLoginAt(),
                existing.createdAt(), Instant.now());
        users.save(deleted);
        invalidateTvCache(id);

        refreshTokens.revokeAllForUser(id);
        singleUseTokens.revokeAllForUser(id);

        audit.save(auditEntry(AuditAction.TENANT_USER_DELETED, actorUserId, tenantId,
                Map.of("tenantUserId", id, "email", originalEmail)));
        log.info("tenant user deleted tenant={} id={} actor={}", tenantId, id, actorUserId);
    }

    // ─────────────────────────────────────────────────────────────────────
    // Helpers
    // ─────────────────────────────────────────────────────────────────────

    private User loadInCurrentTenantOrThrow(String id) {
        String tenantId = TenantContext.requireCurrent();
        User u = users.findById(id)
                .orElseThrow(() -> new NotFoundException("tenant user " + id + " not found"));
        if (u.kind() != UserKind.TENANT_USER || !tenantId.equals(u.tenantId())) {
            throw new NotFoundException("tenant user " + id + " not found");
        }
        return u;
    }

    private String currentOwnerId(String tenantId) {
        return tenants.findByIdAndDeletedAtIsNull(tenantId)
                .map(Tenant::ownerUserId)
                .orElse(null);
    }

    private static AuditEntry auditEntry(AuditAction action, String actorUserId,
                                         String tenantId, Map<String, Object> metadata) {
        return new AuditEntry(null, Instant.now(), actorUserId, action,
                null, null, tenantId, Map.copyOf(metadata), null, null);
    }
}
