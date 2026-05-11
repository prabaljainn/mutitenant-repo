package com.orochiverse.platform.iam.admin.tenants;

import java.time.Instant;
import java.util.Map;

import jakarta.validation.constraints.NotBlank;

import com.orochiverse.platform.iam.tenants.Tenant;
import com.orochiverse.platform.iam.tenants.TenantStatus;

/**
 * Request and response DTOs for the {@code /admin/api/tenants} surface.
 *
 * <p>We expose response DTOs (not the persistence record) so we can evolve
 * the storage shape — e.g. add internal fields like {@code billingProvider}
 * — without breaking the API contract. The mapping is intentionally
 * mechanical; no field is computed or hidden today.
 */
public final class TenantDtos {

    private TenantDtos() {}

    /**
     * Bean-Validation runs on {@code id} via the {@link TenantId regex}
     * downstream — we don't repeat the pattern here so the rule lives in
     * one place, but we do reject blank up front for a cleaner 400.
     */
    public record CreateTenantRequest(
            @NotBlank String id,
            @NotBlank String name,
            @NotBlank String plan) {}

    /**
     * Partial update — null fields are left alone. Allows reusing the
     * single endpoint for "rename", "change plan", "edit settings"
     * without three separate routes.
     */
    public record UpdateTenantRequest(
            String name,
            String plan,
            Map<String, Object> settings) {}

    public record TenantResponse(
            String id,
            String name,
            TenantStatus status,
            String plan,
            Map<String, Object> settings,
            String createdBy,
            Instant createdAt,
            Instant updatedAt) {

        public static TenantResponse from(Tenant t) {
            return new TenantResponse(t.id(), t.name(), t.status(), t.plan(),
                    t.settings(), t.createdBy(), t.createdAt(), t.updatedAt());
        }
    }
}
