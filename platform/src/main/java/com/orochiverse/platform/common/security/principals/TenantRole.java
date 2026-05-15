package com.orochiverse.platform.common.security.principals;

/**
 * Role of a {@link UserKind#TENANT_USER} within their (single) tenant.
 *
 * <p>Ownership is no longer a role — it lives as
 * {@code Tenant.ownerUserId} on the tenant document itself. That keeps
 * the role enum focused on "what can this user do" and the tenant doc
 * authoritative on "who owns this tenant."
 */
public enum TenantRole {
    /** Manage users, settings, and content within the tenant. */
    ADMIN,
    /** Read + write content. No user management or settings access. */
    MEMBER
}
