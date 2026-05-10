package com.orochiverse.platform.iam.users;

/**
 * Lifecycle states for any platform user (operator or tenant user).
 *
 * <p>Status transitions are not enforced here — those rules live in the
 * services that drive lifecycle events (invite acceptance, password reset,
 * suspension, etc.).
 */
public enum UserStatus {
    /** Created but the invite hasn't been accepted yet (no password set). */
    INVITED,
    /** Active, can log in. */
    ACTIVE,
    /** Login disabled by an admin or by lockout. */
    SUSPENDED,
    /** Soft-deleted. Email becomes reusable for a new account. */
    DELETED
}
