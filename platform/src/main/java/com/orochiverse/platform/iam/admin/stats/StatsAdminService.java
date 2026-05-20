package com.orochiverse.platform.iam.admin.stats;

import java.util.Set;

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Service;

import com.orochiverse.platform.common.security.principals.UserKind;
import com.orochiverse.platform.iam.admin.common.OperatorVisibility;
import com.orochiverse.platform.iam.admin.stats.StatsDtos.OverviewStats;
import com.orochiverse.platform.iam.tenants.TenantRepository;
import com.orochiverse.platform.iam.users.UserRepository;
import com.orochiverse.platform.iam.users.UserStatus;

/**
 * Aggregates the counts the admin Overview screen needs.
 *
 * <p>Two paths: ADMIN sees global counts (cheap, three Mongo {@code count}
 * commands). SUPPORT sees the same counters scoped to the tenants they're
 * assigned to — empty assignments → all zeros.
 */
@Service
@ConditionalOnProperty(prefix = "spring.data.mongodb", name = "uri")
public class StatsAdminService {

    private final TenantRepository tenants;
    private final UserRepository users;
    private final OperatorVisibility visibility;

    public StatsAdminService(TenantRepository tenants,
                             UserRepository users,
                             OperatorVisibility visibility) {
        this.tenants = tenants;
        this.users = users;
        this.visibility = visibility;
    }

    public OverviewStats overview() {
        Set<String> allowedIds = visibility.visibleTenantIdsOrUnrestricted();
        return allowedIds == null ? globalOverview() : scopedOverview(allowedIds);
    }

    private OverviewStats globalOverview() {
        long tenantCount = tenants.countByDeletedAtIsNull();

        long tenantUserCount = users.countByKindAndStatus(UserKind.TENANT_USER, UserStatus.ACTIVE)
                + users.countByKindAndStatus(UserKind.TENANT_USER, UserStatus.INVITED)
                + users.countByKindAndStatus(UserKind.TENANT_USER, UserStatus.SUSPENDED);

        long pendingInvites = users.countByStatus(UserStatus.INVITED);

        return new OverviewStats(tenantCount, tenantUserCount, pendingInvites);
    }

    private OverviewStats scopedOverview(Set<String> allowedIds) {
        if (allowedIds.isEmpty()) {
            return new OverviewStats(0, 0, 0);
        }
        long tenantCount = tenants.countByDeletedAtIsNullAndIdIn(allowedIds);

        // Tenant users in the assigned tenants — all non-deleted statuses
        // mirror the global path's three-status sum.
        long tenantUserCount = users.countByKindAndTenantIdIn(UserKind.TENANT_USER, allowedIds)
                - users.countByKindAndStatusAndTenantIdIn(
                        UserKind.TENANT_USER, UserStatus.DELETED, allowedIds);

        // Pending invites scoped to assigned tenants. Operator invites are
        // cross-tenant by nature so we don't count them here.
        long pendingInvites = users.countByKindAndStatusAndTenantIdIn(
                UserKind.TENANT_USER, UserStatus.INVITED, allowedIds);

        return new OverviewStats(tenantCount, tenantUserCount, pendingInvites);
    }
}
