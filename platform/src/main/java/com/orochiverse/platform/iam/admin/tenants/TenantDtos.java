package com.orochiverse.platform.iam.admin.tenants;

import java.time.Instant;
import java.util.Map;

import jakarta.validation.constraints.NotBlank;

import com.orochiverse.platform.iam.tenants.Tenant;

/**
 * Request and response DTOs for the {@code /admin/api/tenants} surface.
 *
 * <p>Response DTOs are separate from the persistence record so the storage
 * shape can evolve without breaking the API contract. {@code deletedAt} is
 * intentionally omitted — soft-deleted tenants are filtered out of every
 * read path, so the response shape only ever describes a live tenant.
 */
public final class TenantDtos {

    private TenantDtos() {}

    public record CreateTenantRequest(
            @NotBlank String name) {}

    /**
     * Partial update — null fields are left alone. Lets a single endpoint
     * handle rename or settings edits without separate routes.
     */
    public record UpdateTenantRequest(
            String name,
            Map<String, Object> settings) {}

    public record TenantResponse(
            String id,
            String name,
            Map<String, Object> settings,
            String ownerUserId,
            String createdBy,
            Instant createdAt,
            Instant updatedAt) {

        public static TenantResponse from(Tenant t) {
            return new TenantResponse(t.id(), t.name(), t.settings(), t.ownerUserId(),
                    t.createdBy(), t.createdAt(), t.updatedAt());
        }
    }
}
