package com.orochiverse.platform.iam.auth;

import java.util.Optional;

/**
 * Storage interface for refresh tokens. Lives behind an interface so the
 * Phase 1.7 {@link InMemoryRefreshTokenStore} (process-local; tokens vanish
 * on restart) can be replaced with a Redis-backed implementation in
 * Phase 1.10 without touching {@link AuthService}.
 *
 * <h2>Lifecycle</h2>
 * <ol>
 *   <li>{@link #issue(String)} mints a fresh random 256-bit token, records
 *       it as belonging to {@code userId}, and returns it. Caller is
 *       expected to hand the token back to the client.</li>
 *   <li>{@link #consume(String)} atomically looks up the token, removes it
 *       from the store, and returns the bound user — i.e. consume-on-use
 *       so a leaked refresh token is single-shot. Returns empty if missing
 *       or expired.</li>
 *   <li>{@link #revoke(String)} explicit cancellation (logout); idempotent.</li>
 *   <li>{@link #revokeAllForUser(String)} blunt instrument used when the
 *       user's password changes or the account is suspended.</li>
 * </ol>
 *
 * <p>Refresh tokens are NOT JWTs — they're opaque random strings. The store
 * is the only place that knows the user binding.
 */
public interface RefreshTokenStore {

    /** Mint and persist a fresh refresh token for {@code userId}. */
    RefreshToken issue(String userId);

    /**
     * Atomically look up + remove the token. Returns the bound entry on
     * success; empty if the token is unknown or already expired. Removing
     * on read is the rotation primitive — the caller mints a new one to
     * hand back to the client.
     */
    Optional<RefreshToken> consume(String token);

    /** Idempotent best-effort revocation. Used by {@code POST /logout}. */
    void revoke(String token);

    /** Used on password change / account deactivation. */
    void revokeAllForUser(String userId);
}
