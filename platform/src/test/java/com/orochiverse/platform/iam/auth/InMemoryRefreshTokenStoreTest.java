package com.orochiverse.platform.iam.auth;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;

import org.junit.jupiter.api.Test;

class InMemoryRefreshTokenStoreTest {

    private static final Instant FIXED = Instant.parse("2026-05-11T12:00:00Z");
    private static final Duration TTL = Duration.ofDays(30);

    @Test
    void issue_then_consume_returns_the_user() {
        var store = new InMemoryRefreshTokenStore(Clock.fixed(FIXED, ZoneOffset.UTC), TTL);
        var token = store.issue("u-1");

        var consumed = store.consume(token.token());
        assertThat(consumed).isPresent();
        assertThat(consumed.get().userId()).isEqualTo("u-1");
        assertThat(consumed.get().expiresAt()).isEqualTo(FIXED.plus(TTL));
    }

    @Test
    void consume_is_single_shot() {
        var store = new InMemoryRefreshTokenStore(Clock.fixed(FIXED, ZoneOffset.UTC), TTL);
        var token = store.issue("u-1");

        assertThat(store.consume(token.token())).isPresent();
        assertThat(store.consume(token.token())).isEmpty();
    }

    @Test
    void consume_returns_empty_for_unknown_token() {
        var store = new InMemoryRefreshTokenStore(Clock.fixed(FIXED, ZoneOffset.UTC), TTL);
        assertThat(store.consume("never-issued")).isEmpty();
        assertThat(store.consume(null)).isEmpty();
        assertThat(store.consume(" ")).isEmpty();
    }

    @Test
    void consume_returns_empty_for_expired_token() {
        var clock = new MutableClock(FIXED);
        var store = new InMemoryRefreshTokenStore(clock, Duration.ofMinutes(1));
        var token = store.issue("u-1");

        clock.advance(Duration.ofMinutes(2));

        assertThat(store.consume(token.token())).isEmpty();
    }

    @Test
    void revoke_is_idempotent() {
        var store = new InMemoryRefreshTokenStore(Clock.fixed(FIXED, ZoneOffset.UTC), TTL);
        var token = store.issue("u-1");

        store.revoke(token.token());
        store.revoke(token.token()); // no throw
        assertThat(store.consume(token.token())).isEmpty();
    }

    @Test
    void revoke_all_for_user_drops_only_that_users_tokens() {
        var store = new InMemoryRefreshTokenStore(Clock.fixed(FIXED, ZoneOffset.UTC), TTL);
        var t1 = store.issue("u-1");
        var t2 = store.issue("u-1");
        var other = store.issue("u-2");

        store.revokeAllForUser("u-1");

        assertThat(store.consume(t1.token())).isEmpty();
        assertThat(store.consume(t2.token())).isEmpty();
        assertThat(store.consume(other.token())).isPresent();
    }

    @Test
    void issued_tokens_are_unique_and_url_safe() {
        var store = new InMemoryRefreshTokenStore(Clock.fixed(FIXED, ZoneOffset.UTC), TTL);
        var a = store.issue("u-1").token();
        var b = store.issue("u-1").token();

        assertThat(a).isNotEqualTo(b);
        // Base64url has [A-Za-z0-9_-]
        assertThat(a).matches("[A-Za-z0-9_-]+");
        assertThat(a.length()).isBetween(40, 50); // 256 bits, no padding ≈ 43 chars
    }

    /** Tiny mutable clock so the expiry test doesn't have to sleep. */
    private static final class MutableClock extends Clock {
        private Instant now;
        MutableClock(Instant start) { this.now = start; }
        void advance(Duration d) { now = now.plus(d); }
        @Override public Instant instant() { return now; }
        @Override public java.time.ZoneId getZone() { return ZoneOffset.UTC; }
        @Override public Clock withZone(java.time.ZoneId zone) { return this; }
    }
}
