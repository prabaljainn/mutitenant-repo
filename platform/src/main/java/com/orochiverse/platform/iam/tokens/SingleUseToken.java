package com.orochiverse.platform.iam.tokens;

import java.time.Instant;
import java.util.Objects;

/**
 * One outstanding invite-accept or password-reset token.
 *
 * <p>Tokens are opaque random strings (NOT JWTs) with meaning only to
 * {@link SingleUseTokenStore}. The {@code purpose} pins what the token
 * does, so a leaked invite token can't be used to reset a password.
 */
public record SingleUseToken(
        String token,
        String userId,
        TokenPurpose purpose,
        Instant issuedAt,
        Instant expiresAt) {

    public SingleUseToken {
        Objects.requireNonNull(token, "token");
        Objects.requireNonNull(userId, "userId");
        Objects.requireNonNull(purpose, "purpose");
        Objects.requireNonNull(issuedAt, "issuedAt");
        Objects.requireNonNull(expiresAt, "expiresAt");
        if (!expiresAt.isAfter(issuedAt)) {
            throw new IllegalArgumentException("expiresAt must be after issuedAt");
        }
    }

    public boolean isExpired(Instant now) {
        return !now.isBefore(expiresAt);
    }
}
