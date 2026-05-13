package com.orochiverse.platform.iam.admin.operators;

import java.time.Instant;

import jakarta.validation.constraints.NotBlank;

import com.orochiverse.platform.iam.operators.OperatorAssignment;

public final class AssignmentDtos {

    private AssignmentDtos() {}

    public record GrantAssignmentRequest(@NotBlank String tenantId) {}

    public record AssignmentResponse(
            String id,
            String operatorUserId,
            String tenantId,
            String assignedBy,
            Instant assignedAt) {

        public static AssignmentResponse from(OperatorAssignment a) {
            return new AssignmentResponse(a.id(), a.operatorUserId(), a.tenantId(),
                    a.assignedBy(), a.assignedAt());
        }
    }
}
