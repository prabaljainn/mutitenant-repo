package com.orochiverse.platform.common.security.jwt;

/**
 * Thrown by {@link AccessTokenVerifier} for any reason a token can't be
 * trusted: bad signature, expired, malformed, wrong issuer, missing claim.
 *
 * <p>Wrapping every jjwt failure mode in one type keeps the Phase 1.6
 * security filter simple — it catches this and returns 401, regardless of
 * what specifically went wrong. We intentionally don't expose the underlying
 * cause to clients; the filter logs it server-side.
 */
public class JwtVerificationException extends RuntimeException {

    public JwtVerificationException(String message) {
        super(message);
    }

    public JwtVerificationException(String message, Throwable cause) {
        super(message, cause);
    }
}
