package com.orochiverse.platform.common.security.passwords;

import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Component;

/**
 * Hash + verify passwords with BCrypt at cost 12 (per design spec §8).
 *
 * <p>BCrypt is the right default for stored credentials in 2026:
 * <ul>
 *   <li><b>Salted by construction.</b> Every hash embeds a random 16-byte
 *       salt, so two users with the same password get different stored
 *       hashes — pre-computed rainbow tables are useless.</li>
 *   <li><b>Tunable work factor.</b> Cost 12 ≈ 250 ms per hash on a 2026-era
 *       server core, which is the sweet spot: fast enough that legitimate
 *       logins feel instant, slow enough that a leaked database is
 *       prohibitively expensive to brute-force.</li>
 *   <li><b>Backwards-compatible upgrades.</b> When we raise the cost in a
 *       future release, the cost is encoded in each stored hash, so old
 *       hashes verify with the old cost and only get re-hashed at the
 *       user's next successful login.</li>
 * </ul>
 *
 * <p>This component is the only place in the codebase that should ever call
 * {@link PasswordEncoder#encode(CharSequence)} or
 * {@link PasswordEncoder#matches(CharSequence, String)} on user passwords —
 * that gives us a single grep target if we ever need to rotate algorithms.
 */
@Component
public class PasswordHashing {

    /** Per design spec §8 ("BCrypt cost 12"). Do not lower without a written ADR. */
    public static final int BCRYPT_COST = 12;

    private final PasswordEncoder encoder;

    public PasswordHashing(PasswordEncoder encoder) {
        this.encoder = encoder;
    }

    /**
     * Hash a freshly-typed password for storage. Returns the {@code $2a$...}
     * BCrypt string, which is what should be persisted in
     * {@code users.passwordHash}.
     *
     * @throws IllegalArgumentException if {@code raw} is null/blank
     */
    public String hash(CharSequence raw) {
        if (raw == null || raw.toString().isBlank()) {
            throw new IllegalArgumentException("password must not be blank");
        }
        return encoder.encode(raw);
    }

    /**
     * Verify a candidate password against a stored hash. Returns false (not
     * throws) for a mismatch so that the caller's authentication-failure
     * path is exception-free, and runs in constant time relative to hash
     * length so it doesn't leak per-user timing information.
     */
    public boolean matches(CharSequence raw, String storedHash) {
        if (raw == null || storedHash == null || storedHash.isBlank()) {
            return false;
        }
        return encoder.matches(raw, storedHash);
    }
}
