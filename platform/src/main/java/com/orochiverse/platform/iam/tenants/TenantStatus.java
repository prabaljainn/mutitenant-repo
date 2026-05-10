package com.orochiverse.platform.iam.tenants;

public enum TenantStatus {
    /** Free / evaluation period. */
    TRIAL,
    /** Paying or otherwise active. */
    ACTIVE,
    /** Login disabled but data preserved. */
    SUSPENDED,
    /** Soft-deleted. The per-tenant DB may already be deprovisioned. */
    ARCHIVED
}
