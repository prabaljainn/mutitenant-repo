package com.orochiverse.platform.iam.tokens;

/**
 * Storage for invite-accept and password-reset tokens. Same single-shot
 * rotation contract as {@link com.orochiverse.platform.iam.auth.RefreshTokenStore}
 * — consume removes the token atomically — but with per-purpose TTLs and
 * a purpose tag that prevents cross-flow reuse.
 *
 * <p>Replace with a Redis-backed implementation in Phase 1.10. The
 * interface seam is here.
 */
public interface SingleUseTokenStore {

    /** Mint and persist a fresh token for {@code (userId, purpose)}. */
    SingleUseToken issue(String userId, TokenPurpose purpose);

    /**
     * Atomically look up + remove the token, requiring it to match
     * {@code expectedPurpose}. Returns the bound entry on success.
     *
     * @throws InvalidTokenException for unknown / expired / wrong-purpose /
     *                               already-consumed tokens. Single error
     *                               type so callers can't enumerate state.
     */
    SingleUseToken consume(String token, TokenPurpose expectedPurpose);

    /**
     * Drops every outstanding token for the user across all purposes.
     * Used when an admin suspends/deletes a user — pending invites and
     * reset links should die with the account.
     */
    void revokeAllForUser(String userId);
}
