package com.orochiverse.platform.iam.tenantadmin;

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import com.orochiverse.platform.common.tenant.TenantContext;
import com.orochiverse.platform.iam.settings.SettingsKind;
import com.orochiverse.platform.iam.settings.TenantSettingsDtos.SettingsListResponse;
import com.orochiverse.platform.iam.settings.TenantSettingsDtos.SettingsResponse;
import com.orochiverse.platform.iam.settings.TenantSettingsService;

import io.swagger.v3.oas.annotations.tags.Tag;

/**
 * Tenant-side read view of the per-tenant settings store. Lets a tenant
 * ADMIN confirm how their MQTT / DJI / future integrations are configured
 * — without exposing the values to MEMBER users or to other tenants.
 *
 * <h2>What this surface intentionally omits</h2>
 * No PUT, DELETE, or {@code /test}. Settings remain operator-owned:
 * Orochiverse staff configure the broker on the tenant's behalf, the
 * tenant verifies. Adding tenant-side writes is a small follow-up if
 * self-service becomes a requirement.
 *
 * <h2>Tenancy isolation</h2>
 * The tenant id comes from {@link TenantContext#requireCurrent()},
 * which is populated by the JWT filter from the verified {@code tid}
 * claim. There is no path-param tenant id, so a tenant user
 * structurally cannot ask about another tenant's settings via this
 * controller.
 *
 * <h2>Secrets</h2>
 * Same masking as the operator side: the response omits secret values
 * and lists secret key names in {@code secrets[]}.
 */
@RestController
@RequestMapping("/api/tenant/settings")
@ConditionalOnProperty(prefix = "spring.data.mongodb", name = "uri")
@Tag(name = "Tenant: Settings", description = "Read-only view of the calling "
        + "tenant's integration settings (MQTT, DJI, …). Admin only; "
        + "secrets stay masked.")
public class TenantSettingsController {

    private final TenantSettingsService service;

    public TenantSettingsController(TenantSettingsService service) {
        this.service = service;
    }

    @GetMapping
    @PreAuthorize("hasRole('ADMIN')")
    public SettingsListResponse list() {
        return new SettingsListResponse(service.list(TenantContext.requireCurrent()));
    }

    @GetMapping("/{kind}")
    @PreAuthorize("hasRole('ADMIN')")
    public SettingsResponse get(@PathVariable SettingsKind kind) {
        return service.get(TenantContext.requireCurrent(), kind);
    }
}
