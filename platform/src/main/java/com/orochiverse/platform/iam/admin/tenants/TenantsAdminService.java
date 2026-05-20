package com.orochiverse.platform.iam.admin.tenants;

import java.security.SecureRandom;
import java.text.Normalizer;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

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
import com.orochiverse.platform.common.tenant.TenantDatabaseProvisioner;
import com.orochiverse.platform.iam.admin.common.AdminExceptions.ConflictException;
import com.orochiverse.platform.iam.admin.common.AdminExceptions.NotFoundException;
import com.orochiverse.platform.iam.admin.common.AdminExceptions.UnprocessableException;
import com.orochiverse.platform.iam.admin.common.OperatorVisibility;
import com.orochiverse.platform.iam.admin.tenants.TenantDtos.CreateTenantRequest;
import com.orochiverse.platform.iam.admin.tenants.TenantDtos.TenantResponse;
import com.orochiverse.platform.iam.admin.tenants.TenantDtos.UpdateTenantRequest;
import com.orochiverse.platform.iam.tenants.Tenant;
import com.orochiverse.platform.iam.tenants.TenantRepository;
import com.orochiverse.platform.iam.users.User;
import com.orochiverse.platform.iam.users.UserRepository;
import com.orochiverse.platform.iam.users.UserStatus;

/**
 * Tenant lifecycle for operator admins. Each mutation is paired with the
 * physical-DB step (provision on create, drop on delete) and an audit
 * entry. Reads exclude soft-deleted rows.
 *
 * <h2>Create flow</h2>
 * <ol>
 *   <li>Derive the id from {@code name} (slugify, append a short random
 *       suffix if the bare slug is taken). The id is not user-supplied.</li>
 *   <li>Save the tenant document with {@code ownerUserId=null}; the first
 *       ADMIN invited gets auto-promoted later.</li>
 *   <li>Provision the per-tenant Mongo DB.</li>
 *   <li>Audit {@code TENANT_CREATED} + {@code TENANT_DB_PROVISIONED}.</li>
 * </ol>
 *
 * <h2>Soft-delete flow</h2>
 * <ol>
 *   <li>Stamp {@code deletedAt} on the tenant document (preserves audit
 *       trail + assignments).</li>
 *   <li>Drop the per-tenant Mongo DB outright.</li>
 *   <li>Audit {@code TENANT_ARCHIVED} + {@code TENANT_DB_DEPROVISIONED}.</li>
 * </ol>
 * Re-creating a tenant with the same id later requires admin intervention
 * to clear the soft-deleted row first.
 *
 * <h2>Why no rollback if provision fails?</h2>
 * If {@link TenantDatabaseProvisioner#provision(String)} throws, the
 * tenant document is already saved in {@code iam_db}. We accept that
 * inconsistency rather than orchestrate a two-phase commit; the next
 * provision call (idempotent) will heal it.
 */
@Service
@ConditionalOnProperty(prefix = "spring.data.mongodb", name = "uri")
public class TenantsAdminService {

    private static final Logger log = LoggerFactory.getLogger(TenantsAdminService.class);

    private final TenantRepository tenants;
    private final UserRepository users;
    private final TenantDatabaseProvisioner provisioner;
    private final AuditEntryRepository audit;
    private final OperatorVisibility visibility;
    private final org.springframework.beans.factory.ObjectProvider<
            com.orochiverse.platform.iam.settings.TenantSettingsService> settingsCleanup;

    public TenantsAdminService(TenantRepository tenants,
                               UserRepository users,
                               TenantDatabaseProvisioner provisioner,
                               AuditEntryRepository audit,
                               OperatorVisibility visibility,
                               org.springframework.beans.factory.ObjectProvider<
                                       com.orochiverse.platform.iam.settings.TenantSettingsService> settingsCleanup) {
        this.tenants = tenants;
        this.users = users;
        this.provisioner = provisioner;
        this.audit = audit;
        this.visibility = visibility;
        this.settingsCleanup = settingsCleanup;
    }

    public TenantResponse create(CreateTenantRequest req, String actorUserId) {
        String id = generateUniqueId(req.name());

        Tenant saved;
        try {
            saved = tenants.save(Tenant.create(id, req.name(), actorUserId));
        } catch (DuplicateKeyException e) {
            // A concurrent create grabbed the slot between our existsById
            // probe and save. Caller can just retry.
            throw new ConflictException("could not allocate a unique tenant id, retry");
        }

        provisioner.provision(saved.id());

        audit.save(AuditEntry.of(AuditAction.TENANT_CREATED, actorUserId, saved.id(),
                Map.of("tenantId", saved.id(), "name", saved.name())));
        audit.save(AuditEntry.of(AuditAction.TENANT_DB_PROVISIONED, actorUserId, saved.id(),
                Map.of("tenantId", saved.id())));
        log.info("tenant created id={} actor={}", saved.id(), actorUserId);

        return TenantResponse.from(saved);
    }

    public List<TenantResponse> list(String query) {
        List<Tenant> rows;
        boolean hasQuery = query != null && !query.isBlank();
        if (hasQuery) {
            rows = tenants.searchByName(java.util.regex.Pattern.quote(query.trim()));
        } else {
            rows = tenants.findAllByDeletedAtIsNull();
        }
        // Scope to what the caller is allowed to see. Admins see everything
        // (allowedIds == null); SUPPORT only their assigned tenants.
        var allowedIds = visibility.visibleTenantIdsOrUnrestricted();
        if (allowedIds != null) {
            rows = rows.stream().filter(t -> allowedIds.contains(t.id())).toList();
        }
        return rows.stream().map(TenantResponse::from).toList();
    }

    public TenantResponse get(String id) {
        // Throw the same 404 a missing/deleted tenant would so SUPPORT
        // can't enumerate the customer list by probing ids.
        visibility.requireVisibility(id);
        return TenantResponse.from(loadOrThrow(id));
    }

    public TenantResponse update(String id, UpdateTenantRequest req, String actorUserId) {
        Tenant existing = loadOrThrow(id);

        String name = req.name() == null ? existing.name() : req.name();
        Map<String, Object> settings = req.settings() == null ? existing.settings() : req.settings();

        var updated = new Tenant(
                existing.id(), name, settings, existing.ownerUserId(),
                existing.createdBy(), existing.deletedAt(),
                existing.createdAt(), Instant.now());
        var saved = tenants.save(updated);

        var changes = new LinkedHashMap<String, Object>();
        if (req.name() != null) changes.put("name", req.name());
        if (req.settings() != null) changes.put("settings", "<changed>");
        audit.save(AuditEntry.of(AuditAction.TENANT_UPDATED, actorUserId, id,
                Map.of("tenantId", id, "changes", changes)));

        return TenantResponse.from(saved);
    }

    /**
     * Hands tenant ownership to another active ADMIN of this tenant. The
     * previous owner stays as a plain ADMIN — they keep their rights but
     * lose owner-protection. Replaces the manual {@code db.tenants.update}
     * patch we'd otherwise have to ssh in to apply.
     *
     * <h3>Validation</h3>
     * <ol>
     *   <li>Tenant exists (live).</li>
     *   <li>{@code newOwnerUserId} resolves to a user.</li>
     *   <li>That user is a {@code TENANT_USER} of <em>this</em> tenant.</li>
     *   <li>Their role is {@link TenantRole#ADMIN}.</li>
     *   <li>Their status is {@link UserStatus#ACTIVE}.</li>
     *   <li>They are not already the current owner.</li>
     * </ol>
     * Anything else → 422 (or 404 for missing tenant).
     */
    public TenantResponse transferOwnership(String id, String newOwnerUserId, String actorUserId) {
        Tenant existing = loadOrThrow(id);
        String currentOwnerId = existing.ownerUserId();

        if (newOwnerUserId.equals(currentOwnerId)) {
            throw new UnprocessableException(
                    "user " + newOwnerUserId + " already owns this tenant");
        }

        User newOwner = users.findById(newOwnerUserId).orElseThrow(() ->
                new UnprocessableException("user " + newOwnerUserId + " not found"));
        if (newOwner.kind() != UserKind.TENANT_USER || !id.equals(newOwner.tenantId())) {
            throw new UnprocessableException(
                    "user " + newOwnerUserId + " does not belong to tenant " + id);
        }
        if (newOwner.tenantRole() != TenantRole.ADMIN) {
            throw new UnprocessableException(
                    "user " + newOwnerUserId + " must be a tenant ADMIN to receive ownership");
        }
        if (newOwner.status() != UserStatus.ACTIVE) {
            throw new UnprocessableException(
                    "user " + newOwnerUserId + " is not ACTIVE — cannot receive ownership");
        }

        var saved = tenants.save(existing.withOwner(newOwnerUserId));

        var meta = new java.util.LinkedHashMap<String, Object>();
        meta.put("tenantId", id);
        if (currentOwnerId != null) meta.put("from", currentOwnerId);
        meta.put("to", newOwnerUserId);
        audit.save(AuditEntry.of(AuditAction.TENANT_OWNERSHIP_TRANSFERRED, actorUserId, id, meta));
        log.info("tenant ownership transferred tenant={} from={} to={} actor={}",
                id, currentOwnerId, newOwnerUserId, actorUserId);

        return TenantResponse.from(saved);
    }

    public void softDelete(String id, String actorUserId) {
        Tenant existing = loadOrThrow(id);

        tenants.save(existing.withDeleted());

        // Tear down everything keyed by this tenant id: the per-tenant
        // Mongo database first, then the iam_db-side settings rows. The
        // settings cleanup is best-effort — if it blips we still want
        // the audit rows to land, so the orphan rows are recoverable
        // by inspection rather than mystery-state. Both steps are
        // idempotent; a re-delete cleans up whatever's left.
        provisioner.deprovision(id);
        var settings = settingsCleanup.getIfAvailable();
        if (settings != null) {
            try {
                settings.deleteAllForTenant(id);
            } catch (RuntimeException e) {
                log.warn("settings cleanup failed for tenant {} — orphan rows left behind; "
                        + "tenant soft-delete itself succeeded. Re-run delete to clean up.",
                        id, e);
            }
        }

        audit.save(AuditEntry.of(AuditAction.TENANT_ARCHIVED, actorUserId, id,
                Map.of("tenantId", id)));
        audit.save(AuditEntry.of(AuditAction.TENANT_DB_DEPROVISIONED, actorUserId, id,
                Map.of("tenantId", id)));
        log.info("tenant soft-deleted + db dropped id={} actor={}", id, actorUserId);
    }

    private Tenant loadOrThrow(String id) {
        return tenants.findByIdAndDeletedAtIsNull(id)
                .orElseThrow(() -> new NotFoundException("tenant " + id + " not found"));
    }

    // ─── Tenant-id generation ──────────────────────────────────────────────
    //
    // The id is opaque to admins now; we derive it from the display name.
    // It still has to satisfy TenantId.VALID (lowercase alnum + `-`, ≤50)
    // because it becomes a Mongo DB name and JWT claim.

    private static final int MAX_BASE_LENGTH = 40;
    private static final int MAX_SUFFIX_ATTEMPTS = 8;
    private static final int SUFFIX_LENGTH = 4;
    // Avoids look-alikes (0/o, 1/l) so ids stay readable in URLs and logs.
    private static final String SUFFIX_ALPHABET = "abcdefghijkmnpqrstuvwxyz23456789";
    private static final SecureRandom RANDOM = new SecureRandom();

    String generateUniqueId(String name) {
        String base = slugify(name);
        if (base.isEmpty()) base = "tenant";
        if (!tenants.existsById(base)) return base;
        for (int i = 0; i < MAX_SUFFIX_ATTEMPTS; i++) {
            String candidate = base + "-" + randomSuffix();
            if (!tenants.existsById(candidate)) return candidate;
        }
        throw new ConflictException("could not allocate a unique tenant id, retry");
    }

    static String slugify(String name) {
        if (name == null) return "";
        String s = Normalizer.normalize(name, Normalizer.Form.NFKD)
                .toLowerCase(Locale.ROOT)
                .replaceAll("[^a-z0-9]+", "-")
                .replaceAll("^-+|-+$", "");
        if (s.length() > MAX_BASE_LENGTH) {
            s = s.substring(0, MAX_BASE_LENGTH).replaceAll("-+$", "");
        }
        return s;
    }

    private static String randomSuffix() {
        var sb = new StringBuilder(SUFFIX_LENGTH);
        for (int i = 0; i < SUFFIX_LENGTH; i++) {
            sb.append(SUFFIX_ALPHABET.charAt(RANDOM.nextInt(SUFFIX_ALPHABET.length())));
        }
        return sb.toString();
    }
}
