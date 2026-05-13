package com.orochiverse.platform.iam.admin.stats;

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Service;

import com.orochiverse.platform.common.security.principals.UserKind;
import com.orochiverse.platform.iam.admin.stats.StatsDtos.OverviewStats;
import com.orochiverse.platform.iam.tenants.TenantRepository;
import com.orochiverse.platform.iam.tenants.TenantStatus;
import com.orochiverse.platform.iam.users.UserRepository;
import com.orochiverse.platform.iam.users.UserStatus;

/**
 * Aggregates the counts the admin Overview screen needs.
 *
 * <p>One service method, three counts. Each count is a single
 * {@code countByX} on the relevant repository, which translates to one
 * Mongo {@code count} command — cheap enough that we don't bother caching.
 * If this list of counters grows past a handful, the right move is a
 * dedicated aggregation pipeline or a materialized stats document, not
 * a per-counter cache.
 */
@Service
@ConditionalOnProperty(prefix = "spring.data.mongodb", name = "uri")
public class StatsAdminService {

    private final TenantRepository tenants;
    private final UserRepository users;

    public StatsAdminService(TenantRepository tenants, UserRepository users) {
        this.tenants = tenants;
        this.users = users;
    }

    public OverviewStats overview() {
        // "Tenants" on the dashboard means active workspaces — TRIAL and
        // ACTIVE count, ARCHIVED doesn't (the data is gone anyway).
        long tenantCount = tenants.countByStatus(TenantStatus.ACTIVE)
                + tenants.countByStatus(TenantStatus.TRIAL)
                + tenants.countByStatus(TenantStatus.SUSPENDED);

        long tenantUserCount = users.countByKindAndStatus(UserKind.TENANT_USER, UserStatus.ACTIVE)
                + users.countByKindAndStatus(UserKind.TENANT_USER, UserStatus.INVITED)
                + users.countByKindAndStatus(UserKind.TENANT_USER, UserStatus.SUSPENDED);

        long pendingInvites = users.countByStatus(UserStatus.INVITED);

        return new OverviewStats(tenantCount, tenantUserCount, pendingInvites);
    }
}
