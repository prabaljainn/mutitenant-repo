package com.orochiverse.platform.iam.admin.stats;

/**
 * Request/response shapes for the admin dashboard's overview metrics.
 *
 * <p>One record per call rather than three separate counter endpoints —
 * the dashboard renders all three together, and a single round-trip is
 * cheaper than three.
 */
public final class StatsDtos {

    private StatsDtos() {}

    /**
     * Numbers shown across the top of the admin Overview screen.
     *
     * @param tenants        non-archived tenants (TRIAL + ACTIVE + SUSPENDED).
     * @param tenantUsers    tenant users in any status except DELETED.
     * @param pendingInvites users (operator + tenant) currently in INVITED
     *                       status — accounts created but invite not yet
     *                       accepted.
     */
    public record OverviewStats(
            long tenants,
            long tenantUsers,
            long pendingInvites) {}
}
