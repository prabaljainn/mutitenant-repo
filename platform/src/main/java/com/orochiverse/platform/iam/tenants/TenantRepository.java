package com.orochiverse.platform.iam.tenants;

import java.util.List;
import java.util.Optional;

import org.springframework.data.mongodb.repository.MongoRepository;
import org.springframework.data.mongodb.repository.Query;
import org.springframework.stereotype.Repository;

import com.orochiverse.platform.common.data.IamScoped;

@Repository
@IamScoped
public interface TenantRepository extends MongoRepository<Tenant, String> {

    boolean existsById(String id);

    /** Live tenants only. Returns empty if the tenant exists but is soft-deleted. */
    Optional<Tenant> findByIdAndDeletedAtIsNull(String id);

    /** Counts live (non-soft-deleted) tenants — the stat the dashboard wants. */
    long countByDeletedAtIsNull();

    /** Lists every live tenant. Soft-deleted rows are excluded. */
    List<Tenant> findAllByDeletedAtIsNull();

    /**
     * Case-insensitive substring search on tenant name, excluding soft-deleted
     * rows. The regex is anchored at neither end so a query of "logist" matches
     * "Skyhawk Logistics".
     */
    @Query("{ 'name': { $regex: ?0, $options: 'i' }, 'deletedAt': null }")
    List<Tenant> searchByName(String pattern);
}
