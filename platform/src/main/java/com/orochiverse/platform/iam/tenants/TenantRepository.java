package com.orochiverse.platform.iam.tenants;

import java.util.List;

import org.springframework.data.mongodb.repository.MongoRepository;
import org.springframework.stereotype.Repository;

import com.orochiverse.platform.common.data.IamScoped;

@Repository
@IamScoped
public interface TenantRepository extends MongoRepository<Tenant, String> {

    boolean existsById(String id);

    List<Tenant> findAllByStatus(TenantStatus status);
}
