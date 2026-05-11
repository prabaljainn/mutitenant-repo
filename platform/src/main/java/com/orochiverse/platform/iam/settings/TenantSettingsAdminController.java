package com.orochiverse.platform.iam.settings;

import jakarta.validation.Valid;

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import com.orochiverse.platform.common.security.auth.AuthenticatedUser;
import com.orochiverse.platform.iam.settings.SettingsKindHandler.TestResult;
import com.orochiverse.platform.iam.settings.TenantSettingsDtos.SettingsListResponse;
import com.orochiverse.platform.iam.settings.TenantSettingsDtos.SettingsResponse;
import com.orochiverse.platform.iam.settings.TenantSettingsDtos.TestSettingsRequest;
import com.orochiverse.platform.iam.settings.TenantSettingsDtos.TestSettingsResponse;
import com.orochiverse.platform.iam.settings.TenantSettingsDtos.UpsertSettingsRequest;

import io.swagger.v3.oas.annotations.tags.Tag;

/**
 * Operator-facing CRUD for the extensible per-tenant
 * {@link TenantSetting} store. One controller, four verbs, generic
 * over {@link SettingsKind} — adding a new kind is one
 * {@link SettingsKindHandler} bean, no new endpoint.
 *
 * <h2>Routes</h2>
 * <pre>
 *   GET    /admin/api/tenants/{tenantId}/settings              → all kinds
 *   GET    /admin/api/tenants/{tenantId}/settings/{kind}       → one kind
 *   PUT    /admin/api/tenants/{tenantId}/settings/{kind}       → upsert
 *   DELETE /admin/api/tenants/{tenantId}/settings/{kind}       → clear
 *   POST   /admin/api/tenants/{tenantId}/settings/{kind}/test  → probe
 * </pre>
 *
 * <h2>RBAC</h2>
 * Reads open to any operator; mutations require {@code OPERATOR_ADMIN},
 * matching the rest of the admin tenant-write surface. The /test
 * endpoint counts as a mutation (it persists the test result on the
 * row).
 */
@RestController
@RequestMapping("/admin/api/tenants/{tenantId}/settings")
@ConditionalOnProperty(prefix = "spring.data.mongodb", name = "uri")
@Tag(name = "Operator: Tenant Settings", description = "Per-tenant configuration "
        + "store, one record per (tenant, kind). MQTT and DJI are the two kinds "
        + "today; new ones plug in via SettingsKindHandler beans.")
public class TenantSettingsAdminController {

    private final TenantSettingsService service;

    public TenantSettingsAdminController(TenantSettingsService service) {
        this.service = service;
    }

    @GetMapping
    @PreAuthorize("hasRole('OPERATOR')")
    public SettingsListResponse list(@PathVariable String tenantId) {
        return new SettingsListResponse(service.list(tenantId));
    }

    @GetMapping("/{kind}")
    @PreAuthorize("hasRole('OPERATOR')")
    public SettingsResponse get(@PathVariable String tenantId, @PathVariable SettingsKind kind) {
        return service.get(tenantId, kind);
    }

    @PutMapping("/{kind}")
    @PreAuthorize("hasRole('OPERATOR_ADMIN')")
    public SettingsResponse upsert(@PathVariable String tenantId,
                                   @PathVariable SettingsKind kind,
                                   @Valid @RequestBody UpsertSettingsRequest req,
                                   @AuthenticationPrincipal AuthenticatedUser caller) {
        return service.upsert(tenantId, kind, req.values(), caller.claims().userId());
    }

    @DeleteMapping("/{kind}")
    @PreAuthorize("hasRole('OPERATOR_ADMIN')")
    public ResponseEntity<Void> delete(@PathVariable String tenantId,
                                       @PathVariable SettingsKind kind,
                                       @AuthenticationPrincipal AuthenticatedUser caller) {
        service.delete(tenantId, kind, caller.claims().userId());
        return ResponseEntity.noContent().build();
    }

    @PostMapping("/{kind}/test")
    @PreAuthorize("hasRole('OPERATOR_ADMIN')")
    public TestSettingsResponse test(@PathVariable String tenantId,
                                     @PathVariable SettingsKind kind,
                                     @RequestBody(required = false) TestSettingsRequest req,
                                     @AuthenticationPrincipal AuthenticatedUser caller) {
        TestResult result = service.test(tenantId, kind,
                req == null ? null : req.values(),
                caller.claims().userId());
        return new TestSettingsResponse(result.ok(), result.latencyMs(), result.error());
    }
}
