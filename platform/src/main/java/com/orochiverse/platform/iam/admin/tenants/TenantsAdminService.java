package com.orochiverse.platform.iam.admin.tenants;

import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Service;

import com.orochiverse.platform.common.audit.AuditAction;
import com.orochiverse.platform.common.audit.AuditEntry;
import com.orochiverse.platform.common.audit.AuditEntryRepository;
import com.orochiverse.platform.common.tenant.TenantDatabaseProvisioner;
import com.orochiverse.platform.common.tenant.TenantId;
import com.orochiverse.platform.iam.admin.common.AdminExceptions.ConflictException;
import com.orochiverse.platform.iam.admin.common.AdminExceptions.NotFoundException;
import com.orochiverse.platform.iam.admin.tenants.TenantDtos.CreateTenantRequest;
import com.orochiverse.platform.iam.admin.tenants.TenantDtos.TenantResponse;
import com.orochiverse.platform.iam.admin.tenants.TenantDtos.UpdateTenantRequest;
import com.orochiverse.platform.iam.tenants.Tenant;
import com.orochiverse.platform.iam.tenants.TenantRepository;

/**
 * Tenant lifecycle for operator admins. Each mutation is paired with the
 * physical-DB step (provision on create, drop on delete) and an audit
 * entry. Reads exclude soft-deleted rows.
 *
 * <h2>Create flow</h2>
 * <ol>
 *   <li>Validate id format (deferred to {@link Tenant}'s constructor).</li>
 *   <li>Reject if id already exists in {@code iam_db.tenants} so the
 *       caller gets a clean 409 instead of a generic duplicate-key error.</li>
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
    private final TenantDatabaseProvisioner provisioner;
    private final AuditEntryRepository audit;
    private final org.springframework.beans.factory.ObjectProvider<
            com.orochiverse.platform.iam.settings.TenantSettingsService> settingsCleanup;

    public TenantsAdminService(TenantRepository tenants,
                               TenantDatabaseProvisioner provisioner,
                               AuditEntryRepository audit,
                               org.springframework.beans.factory.ObjectProvider<
                                       com.orochiverse.platform.iam.settings.TenantSettingsService> settingsCleanup) {
        this.tenants = tenants;
        this.provisioner = provisioner;
        this.audit = audit;
        this.settingsCleanup = settingsCleanup;
    }

    public TenantResponse create(CreateTenantRequest req, String actorUserId) {
        TenantId.requireValid(req.id());

        if (tenants.existsById(req.id())) {
            throw new ConflictException("tenant " + req.id() + " already exists");
        }

        Tenant saved;
        try {
            saved = tenants.save(Tenant.create(req.id(), req.name(), actorUserId));
        } catch (DuplicateKeyException e) {
            throw new ConflictException("tenant " + req.id() + " already exists");
        }

        provisioner.provision(saved.id());

        audit.save(AuditEntry.of(AuditAction.TENANT_CREATED, actorUserId,
                Map.of("tenantId", saved.id(), "name", saved.name())));
        audit.save(AuditEntry.of(AuditAction.TENANT_DB_PROVISIONED, actorUserId,
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
        return rows.stream().map(TenantResponse::from).toList();
    }

    public TenantResponse get(String id) {
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
        audit.save(AuditEntry.of(AuditAction.TENANT_UPDATED, actorUserId,
                Map.of("tenantId", id, "changes", changes)));

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

        audit.save(AuditEntry.of(AuditAction.TENANT_ARCHIVED, actorUserId,
                Map.of("tenantId", id)));
        audit.save(AuditEntry.of(AuditAction.TENANT_DB_DEPROVISIONED, actorUserId,
                Map.of("tenantId", id)));
        log.info("tenant soft-deleted + db dropped id={} actor={}", id, actorUserId);
    }

    private Tenant loadOrThrow(String id) {
        return tenants.findByIdAndDeletedAtIsNull(id)
                .orElseThrow(() -> new NotFoundException("tenant " + id + " not found"));
    }
}
