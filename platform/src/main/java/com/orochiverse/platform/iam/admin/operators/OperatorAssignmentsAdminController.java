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
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import com.orochiverse.platform.common.security.auth.AuthenticatedUser;
import com.orochiverse.platform.iam.admin.operators.AssignmentDtos.AssignmentResponse;
import com.orochiverse.platform.iam.admin.operators.AssignmentDtos.GrantAssignmentRequest;

/**
 * {@code /admin/api/operators/{operatorId}/assignments} — manage which
 * tenants an operator can act in.
 */
@RestController
@RequestMapping("/admin/api/operators/{operatorId}/assignments")
@ConditionalOnProperty(prefix = "spring.data.mongodb", name = "uri")
public class OperatorAssignmentsAdminController {

    private final OperatorAssignmentsAdminService service;

    public OperatorAssignmentsAdminController(OperatorAssignmentsAdminService service) {
        this.service = service;
    }

    @PostMapping
    @PreAuthorize("hasRole('OPERATOR_ADMIN')")
    public ResponseEntity<AssignmentResponse> grant(@PathVariable String operatorId,
                                                    @Valid @RequestBody GrantAssignmentRequest req,
                                                    @AuthenticationPrincipal AuthenticatedUser caller) {
        var resp = service.grant(operatorId, req.tenantId(), caller.claims().userId());
        return ResponseEntity.status(HttpStatus.CREATED).body(resp);
    }

    @GetMapping
    @PreAuthorize("hasRole('OPERATOR')")
    public List<AssignmentResponse> list(@PathVariable String operatorId) {
        return service.listForOperator(operatorId);
    }

    @DeleteMapping("/{tenantId}")
    @PreAuthorize("hasRole('OPERATOR_ADMIN')")
    public ResponseEntity<Void> revoke(@PathVariable String operatorId,
                                       @PathVariable String tenantId,
                                       @AuthenticationPrincipal AuthenticatedUser caller) {
        service.revoke(operatorId, tenantId, caller.claims().userId());
        return ResponseEntity.noContent().build();
    }
}
