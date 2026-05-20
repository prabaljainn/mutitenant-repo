package com.orochiverse.platform.iam.tenantadmin;

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
import com.orochiverse.platform.iam.tenantadmin.TenantSelfDtos.InviteTenantUserRequest;
import com.orochiverse.platform.iam.tenantadmin.TenantSelfDtos.TenantUserResponse;
import com.orochiverse.platform.iam.tenantadmin.TenantSelfDtos.UpdateTenantUserRequest;
import com.orochiverse.platform.iam.users.UserStatus;

import io.swagger.v3.oas.annotations.tags.Tag;

/**
 * {@code /api/tenant/users} — tenant-side user management. The operating
 * tenant is taken from {@code TenantContext} (bound by the JWT filter
 * from the verified {@code tid} claim), so tenant ids never appear in
 * these URLs.
 *
 * <p>Reads require any {@code TENANT_USER} kind; writes require
 * {@code ADMIN} role within the tenant. Members are read-only for the
 * user-management surface.
 */
@RestController
@RequestMapping("/api/tenant/users")
@ConditionalOnProperty(prefix = "spring.data.mongodb", name = "uri")
@Tag(name = "Tenant: Users", description = "Self-service tenant user CRUD. "
        + "Reads open to any tenant user; writes require ADMIN.")
public class TenantUsersController {

    private final TenantUsersService service;

    public TenantUsersController(TenantUsersService service) {
        this.service = service;
    }

    @PostMapping
    @PreAuthorize("hasRole('ADMIN')")
    public ResponseEntity<TenantUserResponse> invite(
            @Valid @RequestBody InviteTenantUserRequest req,
            @AuthenticationPrincipal AuthenticatedUser caller) {
        var resp = service.invite(req, caller.claims().userId());
        return ResponseEntity.status(HttpStatus.CREATED).body(resp);
    }

    @GetMapping
    @PreAuthorize("hasRole('TENANT_USER')")
    public List<TenantUserResponse> list(@RequestParam(required = false) UserStatus status) {
        return service.list(status);
    }

    @GetMapping("/{id}")
    @PreAuthorize("hasRole('TENANT_USER')")
    public TenantUserResponse get(@PathVariable String id) {
        return service.get(id);
    }

    @PutMapping("/{id}")
    @PreAuthorize("hasRole('ADMIN')")
    public TenantUserResponse update(@PathVariable String id,
                                     @Valid @RequestBody UpdateTenantUserRequest req,
                                     @AuthenticationPrincipal AuthenticatedUser caller) {
        return service.update(id, req, caller.claims().userId());
    }

    @DeleteMapping("/{id}")
    @PreAuthorize("hasRole('ADMIN')")
    public ResponseEntity<Void> delete(@PathVariable String id,
                                       @AuthenticationPrincipal AuthenticatedUser caller) {
        service.softDelete(id, caller.claims().userId());
        return ResponseEntity.noContent().build();
    }
}
