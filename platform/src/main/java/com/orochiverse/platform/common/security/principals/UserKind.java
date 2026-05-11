package com.orochiverse.platform.common.security.principals;

/**
 * The two top-level user shapes in the platform.
 *
 * <p>Lives in {@code common.security.principals} (not {@code iam.users}) because
 * it's part of the JWT contract — every module that authorizes a request,
 * including future modules outside {@code iam}, has to read this from the
 * token's {@code kind} claim. Keeping the enum in {@code common} avoids a
 * forced {@code iam} dependency just to interpret a principal.
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
