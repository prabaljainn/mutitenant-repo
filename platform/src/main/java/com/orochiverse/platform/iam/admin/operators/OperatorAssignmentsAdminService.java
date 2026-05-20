package com.orochiverse.platform.iam.admin.operators;

import java.util.List;
import java.util.Map;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Service;

import com.orochiverse.platform.common.audit.AuditAction;
import com.orochiverse.platform.common.audit.AuditEntry;
import com.orochiverse.platform.common.audit.AuditEntryRepository;
import com.orochiverse.platform.common.security.principals.UserKind;
import com.orochiverse.platform.common.tenant.TenantId;
import com.orochiverse.platform.iam.admin.common.AdminExceptions.ConflictException;
import com.orochiverse.platform.iam.admin.common.AdminExceptions.NotFoundException;
import com.orochiverse.platform.iam.admin.common.AdminExceptions.UnprocessableException;
import com.orochiverse.platform.iam.admin.operators.AssignmentDtos.AssignmentResponse;
import com.orochiverse.platform.iam.operators.OperatorAssignment;
import com.orochiverse.platform.iam.operators.OperatorAssignmentRepository;
import com.orochiverse.platform.iam.tenants.TenantRepository;
import com.orochiverse.platform.iam.users.UserRepository;

/**
 * Grant / revoke / list which tenants an operator is allowed to act in.
 *
 * <p>The compound unique index {@code (operatorUserId, tenantId)}
 * (Phase 1.4 Mongock) makes duplicate grants impossible at the DB; we
 * still pre-check with {@code existsBy...} so the caller gets a clean
 * 409 instead of a generic Mongo error.
 */
@Service
@ConditionalOnProperty(prefix = "spring.data.mongodb", name = "uri")
public class OperatorAssignmentsAdminService {

    private static final Logger log = LoggerFactory.getLogger(OperatorAssignmentsAdminService.class);

    private final OperatorAssignmentRepository assignments;
    private final UserRepository users;
    private final TenantRepository tenants;
    private final AuditEntryRepository audit;

    public OperatorAssignmentsAdminService(OperatorAssignmentRepository assignments,
                                           UserRepository users,
                                           TenantRepository tenants,
                                           AuditEntryRepository audit) {
        this.assignments = assignments;
        this.users = users;
        this.tenants = tenants;
        this.audit = audit;
    }

    public AssignmentResponse grant(String operatorUserId, String tenantId, String actorUserId) {
        TenantId.requireValid(tenantId);

        var operator = users.findById(operatorUserId)
                .orElseThrow(() -> new NotFoundException("operator " + operatorUserId + " not found"));
        if (operator.kind() != UserKind.OPERATOR) {
            throw new UnprocessableException("user " + operatorUserId + " is not an operator");
        }
        if (!tenants.existsById(tenantId)) {
            throw new NotFoundException("tenant " + tenantId + " not found");
        }
        if (assignments.existsByOperatorUserIdAndTenantId(operatorUserId, tenantId)) {
            throw new ConflictException("operator " + operatorUserId
                    + " is already assigned to tenant " + tenantId);
        }

        OperatorAssignment saved;
        try {
            saved = assignments.save(OperatorAssignment.grant(operatorUserId, tenantId, actorUserId));
        } catch (DuplicateKeyException e) {
            throw new ConflictException("operator " + operatorUserId
                    + " is already assigned to tenant " + tenantId);
        }

        audit.save(AuditEntry.of(AuditAction.OPERATOR_ASSIGNMENT_GRANTED, actorUserId, tenantId,
                Map.of("operatorId", operatorUserId, "tenantId", tenantId)));
        log.info("operator assignment granted operator={} tenant={} actor={}",
                operatorUserId, tenantId, actorUserId);
        return AssignmentResponse.from(saved);
    }

    public List<AssignmentResponse> listForOperator(String operatorUserId) {
        if (!users.existsById(operatorUserId)) {
            throw new NotFoundException("operator " + operatorUserId + " not found");
        }
        return assignments.findAllByOperatorUserId(operatorUserId).stream()
                .map(AssignmentResponse::from).toList();
    }

    public void revoke(String operatorUserId, String tenantId, String actorUserId) {
        long deleted = assignments.deleteByOperatorUserIdAndTenantId(operatorUserId, tenantId);
        if (deleted == 0) {
            throw new NotFoundException("no assignment for operator " + operatorUserId
                    + " in tenant " + tenantId);
        }
        audit.save(AuditEntry.of(AuditAction.OPERATOR_ASSIGNMENT_REVOKED, actorUserId, tenantId,
                Map.of("operatorId", operatorUserId, "tenantId", tenantId)));
        log.info("operator assignment revoked operator={} tenant={} actor={}",
                operatorUserId, tenantId, actorUserId);
    }
}
