package com.orochiverse.platform.iam.auth;

import java.time.Duration;
import java.util.concurrent.atomic.AtomicInteger;

import org.springframework.stereotype.Component;

import com.github.benmanes.caffeine.cache.Cache;
import com.github.benmanes.caffeine.cache.Caffeine;

/**
 * Brute-force login throttle: 5 failed attempts per email+IP within a
 * 15-minute sliding window (per spec §8). Caffeine handles TTL eviction
 * so we don't need a background sweeper.
 *
 * <h2>Why per (email, ip) and not per email alone?</h2>
 * <ul>
 *   <li>Per-email-only would let one rogue IP lock out a real user
 *       trivially — a denial-of-service vector.</li>
 *   <li>Per-IP-only would let a botnet attack one account by cycling
 *       source IPs.</li>
 * </ul>
 * The composite key handles both: locking out (alice, 1.2.3.4) doesn't
 * affect (alice, 5.6.7.8), and the same IP attacking 100 different
 * emails gets 5 free attempts each but each pair is still independently
 * counted.
 *
 * <h2>What "success" does</h2>
 * A successful login is the right reset signal: clear the (email, ip)
 * counter so a typo or two doesn't become a permanent lockout.
 *
 * <p>Replace with a Redis-backed implementation in the M2 hardening pass
 * for horizontal scale.
 */
@Component
public class LoginRateLimiter {

    public static final int MAX_FAILED_ATTEMPTS = 5;
    public static final Duration WINDOW = Duration.ofMinutes(15);

    private final Cache<String, AtomicInteger> attempts = Caffeine.newBuilder()
            .expireAfterWrite(WINDOW)
            .maximumSize(100_000)
            .build();

    /** @throws RateLimitExceededException if the (email, ip) pair is locked out. */
    public void check(String email, String ip) {
        AtomicInteger counter = attempts.getIfPresent(key(email, ip));
        if (counter != null && counter.get() >= MAX_FAILED_ATTEMPTS) {
            throw new RateLimitExceededException(
                    "too many failed login attempts; try again later");
        }
    }

    /** Record one failed attempt. Called only when login fails. */
    public void recordFailure(String email, String ip) {
        attempts.get(key(email, ip), k -> new AtomicInteger()).incrementAndGet();
    }

    /** Successful login wipes the counter so honest typos don't accumulate. */
    public void recordSuccess(String email, String ip) {
        attempts.invalidate(key(email, ip));
    }

    private static String key(String email, String ip) {
        // Lower-case the email so case differences don't create separate buckets.
        return (email == null ? "" : email.toLowerCase()) + "|" + (ip == null ? "" : ip);
    }
}
