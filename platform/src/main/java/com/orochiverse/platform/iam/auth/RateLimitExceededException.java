package com.orochiverse.platform.iam.auth;

/**
 * Login attempt rejected because the caller has exceeded the brute-force
 * threshold (5 failures per 15 minutes per email+IP, per spec §8). Mapped
 * to HTTP 429 by {@link AuthExceptionHandler}.
 *
 * <p>Distinct from {@link InvalidCredentialsException} so the response
 * code can convey "back off" without revealing whether the email or
 * password was the cause of failure.
 */
public class RateLimitExceededException extends RuntimeException {
    public RateLimitExceededException(String message) {
        super(message);
    }
}
