package com.orochiverse.platform.iam.auth;

/**
 * Thrown by {@code POST /api/auth/switch-tenant} when the calling operator
 * has no {@code OperatorAssignment} for the requested tenant. Mapped to
 * HTTP 403 — the caller is authenticated, just not authorized for that
 * tenant.
 */
public class OperatorNotAssignedException extends RuntimeException {
    public OperatorNotAssignedException(String message) {
        super(message);
    }
}
