package com.orochiverse.platform.iam.tokens;

import java.security.SecureRandom;
import java.time.Clock;
import java.time.Instant;
import java.util.Base64;
import java.util.concurrent.ConcurrentHashMap;

import org.springframework.stereotype.Component;

/**
 * Process-local single-use token store. M1 default; Phase 1.10 swaps in a
 * Redis-backed implementation behind the {@link SingleUseTokenStore}
 * interface.
 *
 * <p>Same in-memory caveats as {@link com.orochiverse.platform.iam.auth.InMemoryRefreshTokenStore}:
 * tokens vanish on JVM restart (users have to re-request a fresh invite/
 * reset link), and you can't horizontally scale the platform.
 *
 * <p>Token format: 256 random bits, base64url encoded (no padding).
 */
@Component
public class InMemorySingleUseTokenStore implements SingleUseTokenStore {

    private static final int TOKEN_BYTES = 32; // 256 bits

    private final SecureRandom random = new SecureRandom();
    private final ConcurrentHashMap<String, SingleUseToken> tokens = new ConcurrentHashMap<>();
    private final Clock clock;

    public InMemorySingleUseTokenStore(Clock clock) {
        this.clock = clock;
    }

    @Override
    public SingleUseToken issue(String userId, TokenPurpose purpose) {
        byte[] bytes = new byte[TOKEN_BYTES];
        random.nextBytes(bytes);
        String token = Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);

        Instant now = clock.instant();
        var entry = new SingleUseToken(token, userId, purpose, now, now.plus(purpose.ttl()));
        tokens.put(token, entry);
        return entry;
    }

    @Override
    public SingleUseToken consume(String token, TokenPurpose expectedPurpose) {
        if (token == null || token.isBlank()) {
            throw new InvalidTokenException("token is empty");
        }
        SingleUseToken entry = tokens.remove(token);
        if (entry == null) {
            throw new InvalidTokenException("token is unknown or already used");
        }
        if (entry.isExpired(clock.instant())) {
            throw new InvalidTokenException("token is expired");
        }
        if (entry.purpose() != expectedPurpose) {
            // Defense in depth — the purpose check is also enforced server-
            // side because the client picks the endpoint, but this catches
            // bugs where a service uses the wrong constant.
            throw new InvalidTokenException("token purpose mismatch");
        }
        return entry;
    }

    @Override
    public void revokeAllForUser(String userId) {
        tokens.values().removeIf(t -> t.userId().equals(userId));
    }
}
