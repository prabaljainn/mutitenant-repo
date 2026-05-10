package com.orochiverse.platform.iam.users;

/**
 * The two top-level user shapes in the platform.
 *
 * <ul>
 *   <li>{@link #OPERATOR} — Orochiverse staff. Cross-tenant. Has an
 *       {@link OperatorRole} and a list of assigned tenants (in the
 *       {@code operator_assignments} collection). No {@code tenantId}.</li>
 *   <li>{@link #TENANT_USER} — End user belonging to exactly one customer
 *       tenant. Has a {@code tenantId} and a {@link TenantRole}. No
 *       operator role.</li>
 * </ul>
 */
public enum UserKind {
    OPERATOR,
    TENANT_USER
}
