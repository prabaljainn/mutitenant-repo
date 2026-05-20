package com.orochiverse.platform.iam.tenants;

import java.time.Instant;
import java.util.Map;
import java.util.Objects;

import org.springframework.data.annotation.Id;
import org.springframework.data.mongodb.core.mapping.Document;

import com.orochiverse.platform.common.tenant.TenantId;

/**
 * A customer tenant of the Orochiverse platform.
 *
 * <p>The {@code id} doubles as both the Mongo {@code _id} and the value used
 * everywhere else as the tenant identifier (URL slugs, JWT claims, audit
 * logs, the per-tenant DB name). Validated through {@link TenantId} so it
 * stays a safe identifier across all of those surfaces.
 *
 * <p>Ownership is a property of the tenant, not a role on the user.
 * {@code ownerUserId} points at the tenant user who owns the tenant; it's
 * nullable because the tenant is created by an operator before any tenant
 * users exist. The first invited {@code ADMIN} is auto-promoted to owner.
 *
 * <p>Deletion is soft via {@code deletedAt}: a non-null timestamp means
 * the tenant has been removed and should be invisible to operator
 * listings. Per-tenant DB and settings rows are deprovisioned at the
 * same moment.
 *
 * @param id            stable identifier — also the URL slug. Validated.
 * @param name          human-readable display name.
 * @param settings      tenant-level configuration; flexible JSON subdoc.
 * @param ownerUserId   tenant user who owns this tenant; null until first ADMIN invited.
 * @param createdBy     userId of the operator who created the tenant.
 * @param deletedAt     soft-delete marker; null = live, non-null = removed.
 * @param createdAt     creation timestamp.
 * @param updatedAt     last modification timestamp.
 */
@Document(collection = "tenants")
public record Tenant(
        @Id String id,
        String name,
        Map<String, Object> settings,
        String ownerUserId,
        String createdBy,
        Instant deletedAt,
        Instant createdAt,
        Instant updatedAt) {

    public Tenant {
        TenantId.requireValid(id);
        Objects.requireNonNull(name, "name");
        Objects.requireNonNull(createdAt, "createdAt");
        Objects.requireNonNull(updatedAt, "updatedAt");
        if (name.isBlank()) {
            throw new IllegalArgumentException("name must not be blank");
        }
        settings = settings == null ? Map.of() : Map.copyOf(settings);
    }

    /** Convenience factory for fresh tenants (no owner yet, not deleted). */
    public static Tenant create(String id, String name, String createdBy) {
        var now = Instant.now();
        return new Tenant(id, name, Map.of(), null, createdBy, null, now, now);
    }

    /** Returns a copy of this tenant with the owner set. */
    public Tenant withOwner(String ownerUserId) {
        return new Tenant(id, name, settings, ownerUserId, createdBy, deletedAt, createdAt, Instant.now());
    }

    /** Returns a copy of this tenant marked as soft-deleted now. */
    public Tenant withDeleted() {
        var now = Instant.now();
        return new Tenant(id, name, settings, ownerUserId, createdBy, now, createdAt, now);
    }
}
