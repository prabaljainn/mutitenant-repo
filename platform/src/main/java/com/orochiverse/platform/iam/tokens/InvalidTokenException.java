package com.orochiverse.platform.iam.tokens;

/**
 * Thrown when {@link SingleUseTokenStore#consume} can't satisfy a request:
 * unknown token, expired token, wrong purpose, or already-consumed.
 *
 * <p>Single error type for all four so the response can't be used to tell
 * "your invite expired" from "this was already used" — both are private
 * timing details that an attacker shouldn't get to learn.
 */
public class InvalidTokenException extends RuntimeException {
    public InvalidTokenException(String message) {
        super(message);
    }
}
