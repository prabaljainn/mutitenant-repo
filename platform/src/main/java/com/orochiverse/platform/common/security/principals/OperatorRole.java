package com.orochiverse.platform.common.security.principals;

/**
 * Role of an Orochiverse operator user across all tenants they're assigned to.
 *
 * <p>The role is uniform — an operator is either an {@link #OPERATOR_ADMIN}
 * (full control) or {@link #OPERATOR_SUPPORT} (read access plus limited
 * support actions) in every tenant they have an assignment for. Per-tenant
 * role variation isn't supported by design (see spec §2).
 */
public enum OperatorRole {
    /** Full control: tenant CRUD, operator user CRUD, can act as any tenant admin. */
    OPERATOR_ADMIN,
    /** Read-mostly: list tenants/users, view audit logs, perform bounded support actions. */
    OPERATOR_SUPPORT
}
