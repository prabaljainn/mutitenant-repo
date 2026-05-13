package com.orochiverse.platform.iam.auth;

import java.time.Instant;
import java.util.Objects;

/**
 * One outstanding refresh token. Refresh tokens are opaque random strings
 * (NOT JWTs) — they only have meaning to the {@link RefreshTokenStore} that
 * minted them. The trade-off vs. JWT refresh tokens:
 *
 * <ul>
 *   <li><b>Pro:</b> revocation is a single map/Redis delete. JWTs would
 *       force us to keep a denylist of revoked-but-not-yet-expired tokens.</li>
 *   <li><b>Pro:</b> can't be replayed against the platform if leaked unless
 *       the leak target also has access to the store.</li>
 *   <li><b>Con:</b> requires a stateful lookup on every refresh — but
 *       refresh is rare (every 15 minutes per session), so this is fine.</li>
 * </ul>
 *
 * <p>The 256-bit random body is generated in {@link InMemoryRefreshTokenStore};
 * this record is the unit of persistence.
 */
public record RefreshToken(String token, String userId, Instant issuedAt, Instant expiresAt) {

    public RefreshToken {
        Objects.requireNonNull(token, "token");
        Objects.requireNonNull(userId, "userId");
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
