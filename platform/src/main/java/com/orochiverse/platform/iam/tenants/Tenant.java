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
 * @param id          stable identifier — also the URL slug. Validated.
 * @param name        human-readable display name.
 * @param status      lifecycle state (TRIAL → ACTIVE → SUSPENDED → ARCHIVED).
 * @param plan        free-form plan code (e.g. "STARTER", "ENTERPRISE").
 * @param settings    tenant-level configuration; flexible JSON subdoc.
 * @param createdBy   userId of the operator/admin who created the tenant.
 * @param createdAt   creation timestamp.
 * @param updatedAt   last modification timestamp.
 */
@Document(collection = "tenants")
public record Tenant(
        @Id String id,
        String name,
        TenantStatus status,
        String plan,
        Map<String, Object> settings,
        String createdBy,
        Instant createdAt,
        Instant updatedAt) {

    public Tenant {
        TenantId.requireValid(id);
        Objects.requireNonNull(name, "name");
        Objects.requireNonNull(status, "status");
        Objects.requireNonNull(createdAt, "createdAt");
        Objects.requireNonNull(updatedAt, "updatedAt");
        if (name.isBlank()) {
            throw new IllegalArgumentException("name must not be blank");
        }
    }

    /** Convenience factory for fresh tenants in TRIAL status. */
    public static Tenant newTrial(String id, String name, String plan, String createdBy) {
        var now = Instant.now();
        return new Tenant(id, name, TenantStatus.TRIAL, plan, Map.of(), createdBy, now, now);
    }
}
