package com.orochiverse.platform.iam.admin.tenantusers;

import java.util.List;

import jakarta.validation.Valid;

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.http.HttpStatus;
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
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import com.orochiverse.platform.common.security.auth.AuthenticatedUser;
import com.orochiverse.platform.common.tenant.TenantContext;
import com.orochiverse.platform.iam.admin.common.AdminExceptions.NotFoundException;
import com.orochiverse.platform.iam.tenantadmin.TenantSelfDtos.InviteTenantUserRequest;
import com.orochiverse.platform.iam.tenantadmin.TenantSelfDtos.TenantUserResponse;
import com.orochiverse.platform.iam.tenantadmin.TenantSelfDtos.UpdateTenantUserRequest;
import com.orochiverse.platform.iam.tenantadmin.TenantUsersService;
import com.orochiverse.platform.iam.tenants.TenantRepository;
import com.orochiverse.platform.iam.tenants.TenantStatus;
import com.orochiverse.platform.iam.users.UserStatus;

import io.swagger.v3.oas.annotations.tags.Tag;

/**
 * Operator-side tenant-user CRUD. Mirrors the surface of
 * {@link com.orochiverse.platform.iam.tenantadmin.TenantUsersController}
 * but takes the tenant id from the URL path instead of the JWT's
 * {@code tid} claim, so operators don't need to {@code switch-tenant}
 * before managing tenant users from the admin console.
 *
 * <h2>How it reuses the tenant-side service</h2>
 * Each handler wraps its call in
 * {@link TenantContext#callIn(String, java.util.concurrent.Callable)} —
 * the service then runs as if it were a tenant-bound request, with
 * audit entries carrying the right {@code tenantId} field. Zero
 * duplication: every rule {@code TenantUsersService} enforces (owner
 * protection, cross-tenant 404s, role validation) applies here too.
 *
 * <h2>RBAC</h2>
 * <ul>
 *   <li>Reads: any {@code OPERATOR} (operator-support included).</li>
 *   <li>Writes: {@code OPERATOR_ADMIN} only — matches the rest of the
 *       admin tenant-write surface.</li>
 * </ul>
 *
 * <h2>404 on unknown tenant</h2>
 * The tenant must exist (in {@code iam_db.tenants}) before any of these
 * endpoints work — otherwise the bound context would point at a
 * non-existent tenant DB and the service would still happily report
 * "no users", which is misleading. We resolve the tenant up front and
 * 404 if it's missing.
 */
@RestController
@RequestMapping("/admin/api/tenants/{tenantId}/users")
@ConditionalOnProperty(prefix = "spring.data.mongodb", name = "uri")
@Tag(name = "Operator: Tenant Users", description = "Operator-side CRUD for "
        + "tenant users. Saves the round-trip through /api/auth/switch-tenant — "
        + "tenant id is in the URL path, not the JWT.")
public class AdminTenantUsersController {

    private final TenantUsersService service;
    private final TenantRepository tenants;

    public AdminTenantUsersController(TenantUsersService service, TenantRepository tenants) {
        this.service = service;
        this.tenants = tenants;
    }

    @PostMapping
    @PreAuthorize("hasRole('OPERATOR_ADMIN')")
    public ResponseEntity<TenantUserResponse> invite(
            @PathVariable String tenantId,
            @Valid @RequestBody InviteTenantUserRequest req,
            @AuthenticationPrincipal AuthenticatedUser caller) {
        requireActiveTenant(tenantId);
        var resp = TenantContext.callIn(tenantId,
                () -> service.invite(req, caller.claims().userId()));
        return ResponseEntity.status(HttpStatus.CREATED).body(resp);
    }

    @GetMapping
    @PreAuthorize("hasRole('OPERATOR')")
    public List<TenantUserResponse> list(@PathVariable String tenantId,
                                         @RequestParam(required = false) UserStatus status) {
        requireTenantExists(tenantId);
        return TenantContext.callIn(tenantId, () -> service.list(status));
    }

    @GetMapping("/{userId}")
    @PreAuthorize("hasRole('OPERATOR')")
    public TenantUserResponse get(@PathVariable String tenantId, @PathVariable String userId) {
        requireTenantExists(tenantId);
        return TenantContext.callIn(tenantId, () -> service.get(userId));
    }

    @PutMapping("/{userId}")
    @PreAuthorize("hasRole('OPERATOR_ADMIN')")
    public TenantUserResponse update(@PathVariable String tenantId,
                                     @PathVariable String userId,
                                     @Valid @RequestBody UpdateTenantUserRequest req,
                                     @AuthenticationPrincipal AuthenticatedUser caller) {
        requireActiveTenant(tenantId);
        return TenantContext.callIn(tenantId,
                () -> service.update(userId, req, caller.claims().userId()));
    }

    @DeleteMapping("/{userId}")
    @PreAuthorize("hasRole('OPERATOR_ADMIN')")
    public ResponseEntity<Void> delete(@PathVariable String tenantId,
                                       @PathVariable String userId,
                                       @AuthenticationPrincipal AuthenticatedUser caller) {
        requireActiveTenant(tenantId);
        TenantContext.callIn(tenantId, () -> {
            service.softDelete(userId, caller.claims().userId());
            return null;
        });
        return ResponseEntity.noContent().build();
    }

    /** 404 if the tenant document doesn't exist. */
    private void requireTenantExists(String tenantId) {
        if (!tenants.existsById(tenantId)) {
            throw new NotFoundException("tenant " + tenantId + " not found");
        }
    }

    /**
     * 404 if missing; 422 if archived. Writes against an archived tenant
     * land in a dropped DB (assignments etc. still in iam_db but
     * pointing nowhere) — clearer to refuse them up front.
     */
    private void requireActiveTenant(String tenantId) {
        var t = tenants.findById(tenantId)
                .orElseThrow(() -> new NotFoundException("tenant " + tenantId + " not found"));
        if (t.status() == TenantStatus.ARCHIVED) {
            throw new com.orochiverse.platform.iam.admin.common.AdminExceptions
                    .UnprocessableException("tenant " + tenantId + " is archived");
        }
    }
}
