package com.orochiverse.platform.iam.users;

/**
 * Role of a {@link UserKind#TENANT_USER} within their (single) tenant.
 */
public enum TenantRole {
    /** First admin of a tenant; can transfer ownership; full control. */
    TENANT_OWNER,
    /** Manage users, settings, and content within the tenant. */
    ADMIN,
    /** Read + write content. No user management. */
    EDITOR,
    /** Read-only access. */
    VIEWER
}
