package com.orochiverse.platform.iam.admin.tenants;

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
import com.orochiverse.platform.iam.admin.tenants.TenantDtos.CreateTenantRequest;
import com.orochiverse.platform.iam.admin.tenants.TenantDtos.TenantResponse;
import com.orochiverse.platform.iam.admin.tenants.TenantDtos.UpdateTenantRequest;
import com.orochiverse.platform.iam.tenants.TenantStatus;

import io.swagger.v3.oas.annotations.tags.Tag;

/**
 * Operator-facing tenant CRUD. Reads are open to any operator; writes
 * require {@code OPERATOR_ADMIN} per spec §7.
 */
@RestController
@RequestMapping("/admin/api/tenants")
@ConditionalOnProperty(prefix = "spring.data.mongodb", name = "uri")
@Tag(name = "Operator: Tenants", description = "Operator-facing tenant CRUD. "
        + "Read open to any operator; writes require OPERATOR_ADMIN.")
public class TenantsAdminController {

    private final TenantsAdminService service;

    public TenantsAdminController(TenantsAdminService service) {
        this.service = service;
    }

    @PostMapping
    @PreAuthorize("hasRole('OPERATOR_ADMIN')")
    public ResponseEntity<TenantResponse> create(@Valid @RequestBody CreateTenantRequest req,
                                                 @AuthenticationPrincipal AuthenticatedUser caller) {
        var resp = service.create(req, caller.claims().userId());
        return ResponseEntity.status(HttpStatus.CREATED).body(resp);
    }

    @GetMapping
    @PreAuthorize("hasRole('OPERATOR')")
    public List<TenantResponse> list(@RequestParam(required = false) TenantStatus status) {
        return service.list(status);
    }

    @GetMapping("/{id}")
    @PreAuthorize("hasRole('OPERATOR')")
    public TenantResponse get(@PathVariable String id) {
        return service.get(id);
    }

    @PutMapping("/{id}")
    @PreAuthorize("hasRole('OPERATOR_ADMIN')")
    public TenantResponse update(@PathVariable String id,
                                 @Valid @RequestBody UpdateTenantRequest req,
                                 @AuthenticationPrincipal AuthenticatedUser caller) {
        return service.update(id, req, caller.claims().userId());
    }

    @DeleteMapping("/{id}")
    @PreAuthorize("hasRole('OPERATOR_ADMIN')")
    public ResponseEntity<Void> delete(@PathVariable String id,
                                       @AuthenticationPrincipal AuthenticatedUser caller) {
        service.softDelete(id, caller.claims().userId());
        return ResponseEntity.noContent().build();
    }
}
