package com.orochiverse.platform.iam.auth;

/**
 * Login failed: email unknown, password mismatch, or user not in
 * {@code ACTIVE} state. Deliberately one exception type for all three so
 * we don't leak <em>which</em> failed via response body or status — that
 * would let an attacker enumerate valid emails. Mapped to HTTP 401 by
 * {@link AuthExceptionHandler}.
 */
public class InvalidCredentialsException extends RuntimeException {
    public InvalidCredentialsException(String message) {
        super(message);
    }
}
