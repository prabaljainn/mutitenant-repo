package com.orochiverse.platform.common.audit;

/**
 * Audit-loggable actions across the platform. Add cases as features land —
 * keeping the set finite makes audit reports queryable and dashboards
 * sensible. Do not log free-form action strings.
 */
public enum AuditAction {
    LOGIN_SUCCESS,
    LOGIN_FAILURE,
    LOGOUT,

    PASSWORD_RESET_REQUESTED,
    PASSWORD_RESET_COMPLETED,
    PASSWORD_CHANGED,

    TENANT_CREATED,
    TENANT_UPDATED,
    TENANT_ARCHIVED,
    TENANT_DB_PROVISIONED,
    TENANT_DB_DEPROVISIONED,

    OPERATOR_INVITED,
    OPERATOR_INVITE_RESENT,
    OPERATOR_ROLE_CHANGED,
    OPERATOR_SUSPENDED,
    OPERATOR_DELETED,
    OPERATOR_ASSIGNMENT_GRANTED,
    OPERATOR_ASSIGNMENT_REVOKED,

    TENANT_USER_INVITED,
    TENANT_USER_INVITE_RESENT,
    TENANT_USER_ROLE_CHANGED,
    TENANT_USER_SUSPENDED,
    TENANT_USER_DELETED,

    TENANT_OWNERSHIP_TRANSFERRED,

    TENANT_SETTING_UPDATED,
    TENANT_SETTING_DELETED,
    TENANT_SETTING_TESTED,

    // ─── Self-service ──────────────────────────────────────────────────
    // Both apply to operators AND tenant users. PASSWORD_CHANGED and
    // TOKEN_REVOKED above are reused for self-service password change
    // and session revoke; metadata.via="self_service" distinguishes them
    // from the admin-driven equivalents.
    PROFILE_UPDATED,

    TENANT_SWITCHED,
    TOKEN_REVOKED
}
