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
 *
 * <h2>Session-identity metadata</h2>
 * {@code userAgent} and {@code ip} are captured at the request that
 * mints the token so the "active sessions" UI can show the operator
 * something they can recognise ("Chrome on macOS — last seen 12 min ago")
 * rather than only an opaque hash. {@code firstSeenAt} survives token
 * rotation: when a refresh token is rotated, the new entry inherits the
 * previous {@code firstSeenAt} so the row in the UI keeps showing when
 * the session originally started, while {@code issuedAt} reflects the
 * most recent refresh. All three are nullable for backwards-compat
 * callers (tests, password-change rotation that has no request scope).
 */
public record RefreshToken(
        String token,
        String userId,
        String userAgent,
        String ip,
        Instant issuedAt,
        Instant firstSeenAt,
        Instant expiresAt) {

    public RefreshToken {
        Objects.requireNonNull(token, "token");
        Objects.requireNonNull(userId, "userId");
        Objects.requireNonNull(issuedAt, "issuedAt");
        Objects.requireNonNull(expiresAt, "expiresAt");
        if (firstSeenAt == null) {
            // A token always has a first-seen moment; default to the
            // issue time when the caller didn't carry one forward.
            firstSeenAt = issuedAt;
        }
        if (!expiresAt.isAfter(issuedAt)) {
            throw new IllegalArgumentException("expiresAt must be after issuedAt");
        }
    }

    /**
     * Backwards-compatible constructor used by tests and call sites that
     * predate the user-agent / IP fields. Equivalent to passing
     * {@code null} for both, with {@code firstSeenAt} defaulting to
     * {@code issuedAt}.
     */
    public RefreshToken(String token, String userId, Instant issuedAt, Instant expiresAt) {
        this(token, userId, null, null, issuedAt, issuedAt, expiresAt);
    }

    public boolean isExpired(Instant now) {
        return !now.isBefore(expiresAt);
    }
}
