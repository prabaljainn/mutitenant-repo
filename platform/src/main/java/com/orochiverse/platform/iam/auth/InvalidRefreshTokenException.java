package com.orochiverse.platform.iam.auth;

/**
 * Refresh attempted with a token that the {@link RefreshTokenStore} doesn't
 * know about — either it never existed, was already consumed (single-shot
 * rotation), expired, or revoked. Mapped to HTTP 401.
 */
public class InvalidRefreshTokenException extends RuntimeException {
    public InvalidRefreshTokenException(String message) {
        super(message);
    }
}
