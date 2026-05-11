package com.orochiverse.platform.iam.settings;

import java.util.List;
import java.util.Optional;

import org.springframework.data.mongodb.repository.MongoRepository;
import org.springframework.stereotype.Repository;

import com.orochiverse.platform.common.data.IamScoped;

@Repository
@IamScoped
public interface TenantSettingsRepository extends MongoRepository<TenantSetting, String> {

    /** Used by GET {@code /admin/api/tenants/{id}/settings} to list all kinds. */
    List<TenantSetting> findAllByTenantId(String tenantId);

    Optional<TenantSetting> findByTenantIdAndKind(String tenantId, SettingsKind kind);

    /** Cleanup hook for {@code TenantsAdminService.softDelete}. */
    long deleteAllByTenantId(String tenantId);
}
