package com.orochiverse.platform.iam.operators;

import java.util.List;
import java.util.Optional;

import org.springframework.data.mongodb.repository.MongoRepository;
import org.springframework.stereotype.Repository;

import com.orochiverse.platform.common.data.IamScoped;

@Repository
@IamScoped
public interface OperatorAssignmentRepository
        extends MongoRepository<OperatorAssignment, String> {

    Optional<OperatorAssignment> findByOperatorUserIdAndTenantId(
            String operatorUserId, String tenantId);

    List<OperatorAssignment> findAllByOperatorUserId(String operatorUserId);

    List<OperatorAssignment> findAllByTenantId(String tenantId);

    boolean existsByOperatorUserIdAndTenantId(String operatorUserId, String tenantId);

    long deleteByOperatorUserIdAndTenantId(String operatorUserId, String tenantId);
}
