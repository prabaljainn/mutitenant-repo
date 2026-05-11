package com.orochiverse.platform.iam.auth;

import java.time.Duration;

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

import com.github.benmanes.caffeine.cache.Cache;
import com.github.benmanes.caffeine.cache.Caffeine;

import com.orochiverse.platform.common.security.auth.TokenVersionLookup;
import com.orochiverse.platform.iam.users.UserRepository;

/**
 * Looks up the current {@code tokenVersion} for a user, with a short
 * Caffeine cache so {@link JwtAuthenticationFilter} doesn't hit Mongo on
 * every request.
 *
 * <h2>What this protects against</h2>
 * Password change and account suspension both bump or invalidate
 * {@code tokenVersion} in {@code iam_db.users}. Without this check, an
 * attacker who stole an access token still has a valid bearer for the
 * remaining {@code accessTokenTtl} (15 min). With this check, they get
 * one cache window (default 30s) until everything they hold is rejected.
 *
 * <h2>Cache strategy</h2>
 * 30-second expire-after-write window. Rationale:
 * <ul>
 *   <li>Short enough that a password change or suspension propagates fast.</li>
 *   <li>Long enough that bursty traffic from the same user collapses to
 *       a single Mongo read.</li>
 * </ul>
 * Tunable via {@code platform.security.tv-cache.ttl}.
 *
 * <h2>Test mode</h2>
 * Gated on the same Mongo URI property as the rest of {@code iam.auth} —
 * the {@code test} profile runs without this bean and
 * {@link JwtAuthenticationFilter} skips the check (see the optional
 * dependency wiring there).
 */
@Component
@ConditionalOnProperty(prefix = "spring.data.mongodb", name = "uri")
public class TokenVersionResolver implements TokenVersionLookup {

    private final UserRepository users;
    private final Cache<String, Integer> cache;

    public TokenVersionResolver(UserRepository users) {
        this.users = users;
        this.cache = Caffeine.newBuilder()
                .expireAfterWrite(Duration.ofSeconds(30))
                .maximumSize(10_000)
                .build();
    }

    @Override
    public int currentVersion(String userId) {
        return cache.get(userId, id -> users.findById(id)
                .map(u -> u.tokenVersion())
                .orElse(-1));
    }

    @Override
    public void invalidate(String userId) {
        cache.invalidate(userId);
    }
}
