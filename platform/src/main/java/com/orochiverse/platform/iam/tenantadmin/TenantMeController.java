package com.orochiverse.platform.iam.tenantadmin;

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import com.orochiverse.platform.common.security.auth.AuthenticatedUser;
import com.orochiverse.platform.common.tenant.TenantContext;
import com.orochiverse.platform.iam.admin.common.AdminExceptions.NotFoundException;
import com.orochiverse.platform.iam.tenantadmin.TenantSelfDtos.TenantMeResponse;
import com.orochiverse.platform.iam.tenants.TenantRepository;
import com.orochiverse.platform.iam.users.UserRepository;

import io.swagger.v3.oas.annotations.tags.Tag;

/**
 * {@code GET /api/tenant/me} — combined view of the current tenant user
 * and their tenant. Saves the SPA two extra round-trips on first paint
 * (the dashboard typically wants both pieces).
 *
 * <p>Distinct from {@link com.orochiverse.platform.common.security.auth.MeController}
 * ({@code /api/auth/me}) which returns just the JWT principal — this
 * endpoint joins it with the tenant document.
 */
@RestController
@RequestMapping("/api/tenant")
@ConditionalOnProperty(prefix = "spring.data.mongodb", name = "uri")
@Tag(name = "Tenant: Me", description = "Combined principal + tenant view for "
        + "the SPA. Tenant-user-only — operators land 403.")
public class TenantMeController {

    private final UserRepository users;
    private final TenantRepository tenants;

    public TenantMeController(UserRepository users, TenantRepository tenants) {
        this.users = users;
        this.tenants = tenants;
    }

    @GetMapping("/me")
    @PreAuthorize("hasRole('TENANT_USER')")
    public TenantMeResponse me(@AuthenticationPrincipal AuthenticatedUser caller) {
        String tenantId = TenantContext.requireCurrent();

        var user = users.findById(caller.claims().userId())
                .orElseThrow(() -> new NotFoundException("user not found"));
        var tenant = tenants.findById(tenantId)
                .orElseThrow(() -> new NotFoundException("tenant " + tenantId + " not found"));

        return new TenantMeResponse(
                TenantMeResponse.MeUser.from(user),
                TenantMeResponse.MeTenant.from(tenant));
    }
}
