package com.orochiverse.platform.iam.admin.operators;

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
import com.orochiverse.platform.iam.admin.operators.OperatorDtos.InviteOperatorRequest;
import com.orochiverse.platform.iam.admin.operators.OperatorDtos.OperatorResponse;
import com.orochiverse.platform.iam.admin.operators.OperatorDtos.UpdateOperatorRequest;
import com.orochiverse.platform.iam.users.UserStatus;

import io.swagger.v3.oas.annotations.tags.Tag;

/**
 * {@code /admin/api/operators} CRUD. Reads are open to any operator;
 * mutations require {@code OPERATOR_ADMIN}.
 */
@RestController
@RequestMapping("/admin/api/operators")
@ConditionalOnProperty(prefix = "spring.data.mongodb", name = "uri")
@Tag(name = "Operator: Operators", description = "Operator user CRUD: invite, "
        + "list, change role, suspend, soft-delete. Mutations require OPERATOR_ADMIN.")
public class OperatorsAdminController {

    private final OperatorsAdminService service;

    public OperatorsAdminController(OperatorsAdminService service) {
        this.service = service;
    }

    @PostMapping
    @PreAuthorize("hasRole('OPERATOR_ADMIN')")
    public ResponseEntity<OperatorResponse> invite(@Valid @RequestBody InviteOperatorRequest req,
                                                   @AuthenticationPrincipal AuthenticatedUser caller) {
        var resp = service.invite(req, caller.claims().userId());
        return ResponseEntity.status(HttpStatus.CREATED).body(resp);
    }

    @GetMapping
    @PreAuthorize("hasRole('OPERATOR')")
    public List<OperatorResponse> list(@RequestParam(required = false) UserStatus status) {
        return service.list(status);
    }

    @GetMapping("/{id}")
    @PreAuthorize("hasRole('OPERATOR')")
    public OperatorResponse get(@PathVariable String id) {
        return service.get(id);
    }

    @PutMapping("/{id}")
    @PreAuthorize("hasRole('OPERATOR_ADMIN')")
    public OperatorResponse update(@PathVariable String id,
                                   @Valid @RequestBody UpdateOperatorRequest req,
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
