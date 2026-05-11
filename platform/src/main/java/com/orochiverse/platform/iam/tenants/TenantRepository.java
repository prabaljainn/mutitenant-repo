package com.orochiverse.platform.iam.tenants;

import java.util.List;

import org.springframework.data.mongodb.repository.MongoRepository;
import org.springframework.stereotype.Repository;

import com.orochiverse.platform.common.data.IamScoped;

@Repository
@IamScoped
public interface TenantRepository extends MongoRepository<Tenant, String> {

    boolean existsById(String id);

    long countByStatus(TenantStatus status);

    List<Tenant> findAllByStatus(TenantStatus status);

    /**
     * Case-insensitive substring search on tenant name. Used by the admin
     * tenants list filter; the regex is anchored at neither end so a query
     * of "logist" matches "Skyhawk Logistics". Mongo evaluates this against
     * the {@code name} field with the collation flag i (case-insensitive)
     * inline in the pattern — no extra index needed for a list page that
     * scans at most a few hundred tenants.
     */
    @org.springframework.data.mongodb.repository.Query(
            "{ 'name': { $regex: ?0, $options: 'i' } }")
    List<Tenant> searchByName(String pattern);

    @org.springframework.data.mongodb.repository.Query(
            "{ 'status': ?0, 'name': { $regex: ?1, $options: 'i' } }")
    List<Tenant> searchByStatusAndName(TenantStatus status, String pattern);
}
