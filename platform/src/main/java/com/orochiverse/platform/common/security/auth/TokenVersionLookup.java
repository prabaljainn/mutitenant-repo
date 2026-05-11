package com.orochiverse.platform.common.security.auth;

/**
 * SPI for the per-user {@code tokenVersion} lookup that
 * {@link JwtAuthenticationFilter} uses to enforce password-change /
 * account-suspension revocation against in-flight access tokens.
 *
 * <p>The interface lives in {@code common.security.auth} so the filter
 * depends only on this contract — never on the concrete repository.
 * The production implementation
 * ({@code com.orochiverse.platform.iam.auth.TokenVersionResolver})
 * lives in {@code iam.auth} where it can read from
 * {@code iam_db.users}; tests can supply a stub.
 *
 * <p>Optional at the bean level — the {@code test} profile (no Mongo)
 * has no implementation, and the filter no-ops the check in that case.
 */
public interface TokenVersionLookup {

    /**
     * @return the user's current token version, or {@code -1} when the
     *         user no longer exists. The filter rejects when the JWT
     *         claim's tv doesn't match this value.
     */
    int currentVersion(String userId);

    /** Manually invalidate a user's cached entry. Called after writes that
     * bump tokenVersion (password change / suspension) so the new value
     * is read on next verify. */
    void invalidate(String userId);
}
