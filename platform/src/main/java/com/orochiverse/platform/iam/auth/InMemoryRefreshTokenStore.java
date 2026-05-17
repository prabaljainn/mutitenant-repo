package com.orochiverse.platform.iam.auth;

import java.security.SecureRandom;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.Base64;
import java.util.Comparator;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

/**
 * Process-local refresh-token store. Used in M1 dev / test / single-node
 * prod. Replace with a Redis-backed implementation in Phase 1.10 — the
 * {@link RefreshTokenStore} interface is the seam.
 *
 * <p><b>Implications of being in-memory:</b>
 * <ul>
 *   <li>A JVM restart invalidates every outstanding refresh token. Users
 *       stay logged in for at most {@code accessTokenTtl} (15 min) after
 *       a deploy before being forced to re-login.</li>
 *   <li>Horizontally-scaled deployments would mint a token on one node
 *       that another node can't validate. Don't run more than one replica
 *       of the platform until Phase 1.10 lands the Redis store.</li>
 * </ul>
 *
 * <p>Token format: 256 random bits, base64url-encoded (no padding) → 43
 * URL-safe characters. {@link SecureRandom} provides the entropy.
 */
@Component
public class InMemoryRefreshTokenStore implements RefreshTokenStore {

    private static final int TOKEN_BYTES = 32; // 256 bits

    private final SecureRandom random = new SecureRandom();
    private final ConcurrentHashMap<String, RefreshToken> tokens = new ConcurrentHashMap<>();
    private final Clock clock;
    private final Duration ttl;

    public InMemoryRefreshTokenStore(
            Clock clock,
            @Value("${platform.security.refresh-token-ttl:P30D}") Duration ttl) {
        this.clock = clock;
        this.ttl = ttl;
    }

    @Override
    public RefreshToken issue(String userId) {
        byte[] bytes = new byte[TOKEN_BYTES];
        random.nextBytes(bytes);
        String token = Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);

        Instant now = clock.instant();
        var entry = new RefreshToken(token, userId, now, now.plus(ttl));
        tokens.put(token, entry);
        return entry;
    }

    @Override
    public Optional<RefreshToken> consume(String token) {
        if (token == null || token.isBlank()) {
            return Optional.empty();
        }
        RefreshToken entry = tokens.remove(token);
        if (entry == null) {
            return Optional.empty();
        }
        if (entry.isExpired(clock.instant())) {
            return Optional.empty();
        }
        return Optional.of(entry);
    }

    @Override
    public void revoke(String token) {
        if (token == null || token.isBlank()) {
            return;
        }
        tokens.remove(token);
    }

    @Override
    public void revokeAllForUser(String userId) {
        tokens.values().removeIf(t -> t.userId().equals(userId));
    }

    @Override
    public List<SessionInfo> listForUser(String userId) {
        Instant now = clock.instant();
        return tokens.values().stream()
                .filter(t -> t.userId().equals(userId))
                .filter(t -> !t.isExpired(now))
                .sorted(Comparator.comparing(RefreshToken::issuedAt).reversed())
                .map(t -> new SessionInfo(
                        RefreshTokenStore.deriveSessionId(t.token()),
                        t.issuedAt(),
                        t.expiresAt()))
                .toList();
    }

    @Override
    public boolean revokeByIdForUser(String id, String userId) {
        if (id == null || id.isBlank()) {
            return false;
        }
        // Linear scan is fine — a single user has at most a handful of
        // outstanding sessions. A Redis-backed impl will key by id directly.
        for (var entry : tokens.entrySet()) {
            RefreshToken rt = entry.getValue();
            if (!rt.userId().equals(userId)) continue;
            if (RefreshTokenStore.deriveSessionId(rt.token()).equals(id)) {
                tokens.remove(entry.getKey());
                return true;
            }
        }
        return false;
    }
}
