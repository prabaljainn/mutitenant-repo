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
import com.orochiverse.platform.iam.users.User;
import com.orochiverse.platform.iam.users.UserRepository;
import com.orochiverse.platform.iam.users.UserStatus;

/**
 * Tenant-user lifecycle for tenant admins/owners. Every operation is
 * scoped to {@link TenantContext#requireCurrent()} — a tenant admin
 * literally cannot see or touch users in another tenant, even if they
 * forge an id in the URL.
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
 * <h2>TENANT_OWNER invariants</h2>
 * <ul>
 *   <li>You cannot invite a TENANT_OWNER. Owners are seeded at tenant
 *       creation (Phase 1.7b) or installed via the future
 *       ownership-transfer flow.</li>
 *   <li>You cannot promote a user to TENANT_OWNER via update.</li>
 *   <li>You cannot demote / suspend / delete the last active
 *       TENANT_OWNER — that would orphan the tenant.</li>
 * </ul>
 *
 * <h2>Audit</h2>
 * Mutations write {@link AuditAction#TENANT_USER_INVITED},
 * {@code TENANT_USER_ROLE_CHANGED}, {@code TENANT_USER_SUSPENDED},
 * {@code TENANT_USER_DELETED}. The {@code tenantId} field is set so
 * {@code GET /admin/api/audit?tenantId=...} can filter to a single
 * tenant's activity.
 */
@Service
@ConditionalOnProperty(prefix = "spring.data.mongodb", name = "uri")
public class TenantUsersService {

    private static final Logger log = LoggerFactory.getLogger(TenantUsersService.class);

    private final UserRepository users;
    private final RefreshTokenStore refreshTokens;
    private final AuditEntryRepository audit;

    public TenantUsersService(UserRepository users,
                              RefreshTokenStore refreshTokens,
                              AuditEntryRepository audit) {
        this.users = users;
        this.refreshTokens = refreshTokens;
        this.audit = audit;
    }

    // ─────────────────────────────────────────────────────────────────────
    // Invite
    // ─────────────────────────────────────────────────────────────────────

    public TenantUserResponse invite(InviteTenantUserRequest req, String actorUserId) {
        String tenantId = TenantContext.requireCurrent();

        if (req.role() == TenantRole.TENANT_OWNER) {
            throw new UnprocessableException(
                    "TENANT_OWNER cannot be assigned via invite — use the ownership-transfer flow");
        }
        if (users.existsByEmailIgnoreCase(req.email())) {
            // Email is unique across the whole platform (per the iam_db
            // unique index), so we can't reuse one even from a different
            // tenant. Surface as 409 either way.
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

        audit.save(auditEntry(AuditAction.TENANT_USER_INVITED, actorUserId, tenantId,
                Map.of("tenantUserId", id, "email", req.email(), "role", req.role().name())));
        log.info("tenant user invited tenant={} id={} email={} role={} actor={}",
                tenantId, id, req.email(), req.role(), actorUserId);
        return TenantUserResponse.from(saved);
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

        if (req.role() == TenantRole.TENANT_OWNER) {
            throw new UnprocessableException(
                    "promotion to TENANT_OWNER is not supported via update — "
                            + "use the ownership-transfer flow");
        }
        if (req.status() == UserStatus.DELETED) {
            throw new UnprocessableException(
                    "use DELETE /api/tenant/users/{id} to soft-delete a tenant user");
        }

        // Owner-protection: any change that takes the last owner out of
        // play is rejected. We check both role-change-from-owner and
        // status-change-from-active, in either case for the only owner.
        boolean removingOwnerStatus = existing.tenantRole() == TenantRole.TENANT_OWNER
                && (
                    (req.role() != null && req.role() != TenantRole.TENANT_OWNER) ||
                    (req.status() != null && req.status() != UserStatus.ACTIVE)
                );
        if (removingOwnerStatus && countActiveOwners(tenantId) <= 1) {
            throw new UnprocessableException(
                    "cannot demote or suspend the last active TENANT_OWNER");
        }

        String firstName = req.firstName() == null ? existing.firstName() : req.firstName();
        String lastName  = req.lastName()  == null ? existing.lastName()  : req.lastName();
        TenantRole role  = req.role()      == null ? existing.tenantRole() : req.role();
        UserStatus status = req.status()   == null ? existing.status()    : req.status();

        var updated = new User(
                existing.id(), existing.email(), existing.passwordHash(),
                firstName, lastName,
                status, existing.kind(), null,
                existing.tenantId(), role,
                existing.tokenVersion(), existing.lastLoginAt(),
                existing.createdAt(), Instant.now());
        var saved = users.save(updated);

        if (req.role() != null && req.role() != existing.tenantRole()) {
            audit.save(auditEntry(AuditAction.TENANT_USER_ROLE_CHANGED, actorUserId, tenantId,
                    Map.of("tenantUserId", id,
                            "from", existing.tenantRole().name(),
                            "to", req.role().name())));
        }
        if (req.status() != null && req.status() != existing.status()
                && req.status() == UserStatus.SUSPENDED) {
            refreshTokens.revokeAllForUser(id);
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

        if (existing.tenantRole() == TenantRole.TENANT_OWNER
                && countActiveOwners(tenantId) <= 1) {
            throw new UnprocessableException("cannot delete the last active TENANT_OWNER");
        }

        var deleted = new User(
                existing.id(), existing.email(), existing.passwordHash(),
                existing.firstName(), existing.lastName(),
                UserStatus.DELETED, existing.kind(), null,
                existing.tenantId(), existing.tenantRole(),
                existing.tokenVersion(), existing.lastLoginAt(),
                existing.createdAt(), Instant.now());
        users.save(deleted);

        refreshTokens.revokeAllForUser(id);

        audit.save(auditEntry(AuditAction.TENANT_USER_DELETED, actorUserId, tenantId,
                Map.of("tenantUserId", id)));
        log.info("tenant user deleted tenant={} id={} actor={}", tenantId, id, actorUserId);
    }

    // ─────────────────────────────────────────────────────────────────────
    // Helpers
    // ─────────────────────────────────────────────────────────────────────

    /**
     * Loads a user by id, but only if they exist AND belong to the
     * current tenant. Returns 404 in either failure mode so we don't
     * leak cross-tenant existence.
     */
    private User loadInCurrentTenantOrThrow(String id) {
        String tenantId = TenantContext.requireCurrent();
        User u = users.findById(id)
                .orElseThrow(() -> new NotFoundException("tenant user " + id + " not found"));
        if (u.kind() != UserKind.TENANT_USER || !tenantId.equals(u.tenantId())) {
            throw new NotFoundException("tenant user " + id + " not found");
        }
        return u;
    }

    private long countActiveOwners(String tenantId) {
        return users.findAllByTenantIdAndStatus(tenantId, UserStatus.ACTIVE).stream()
                .filter(u -> u.tenantRole() == TenantRole.TENANT_OWNER)
                .count();
    }

    /**
     * Builds an audit entry that carries the tenant id so
     * {@code /admin/api/audit?tenantId=…} surfaces tenant-scoped activity.
     * The {@link AuditEntry#of(AuditAction, String, Map)} factory doesn't
     * accept a tenantId; we use the canonical constructor.
     */
    private static AuditEntry auditEntry(AuditAction action, String actorUserId,
                                         String tenantId, Map<String, Object> metadata) {
        return new AuditEntry(null, Instant.now(), actorUserId, action,
                null, null, tenantId, Map.copyOf(metadata), null, null);
    }
}
