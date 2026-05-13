package com.orochiverse.platform.iam.tokens;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;

import org.junit.jupiter.api.Test;

class InMemorySingleUseTokenStoreTest {

    private static final Instant FIXED = Instant.parse("2026-05-11T12:00:00Z");

    @Test
    void issue_then_consume_returns_the_user() {
        var store = new InMemorySingleUseTokenStore(Clock.fixed(FIXED, ZoneOffset.UTC));
        var token = store.issue("u-1", TokenPurpose.INVITE_ACCEPT);

        var consumed = store.consume(token.token(), TokenPurpose.INVITE_ACCEPT);

        assertThat(consumed.userId()).isEqualTo("u-1");
        assertThat(consumed.purpose()).isEqualTo(TokenPurpose.INVITE_ACCEPT);
        assertThat(consumed.expiresAt()).isEqualTo(FIXED.plus(TokenPurpose.INVITE_ACCEPT.ttl()));
    }

    @Test
    void consume_is_single_shot() {
        var store = new InMemorySingleUseTokenStore(Clock.fixed(FIXED, ZoneOffset.UTC));
        var token = store.issue("u-1", TokenPurpose.PASSWORD_RESET);

        store.consume(token.token(), TokenPurpose.PASSWORD_RESET);

        assertThatThrownBy(() -> store.consume(token.token(), TokenPurpose.PASSWORD_RESET))
                .isInstanceOf(InvalidTokenException.class);
    }

    @Test
    void consume_with_wrong_purpose_is_rejected() {
        var store = new InMemorySingleUseTokenStore(Clock.fixed(FIXED, ZoneOffset.UTC));
        var token = store.issue("u-1", TokenPurpose.INVITE_ACCEPT);

        assertThatThrownBy(() -> store.consume(token.token(), TokenPurpose.PASSWORD_RESET))
                .isInstanceOf(InvalidTokenException.class);
    }

    @Test
    void consume_unknown_token_is_rejected() {
        var store = new InMemorySingleUseTokenStore(Clock.fixed(FIXED, ZoneOffset.UTC));

        assertThatThrownBy(() -> store.consume("never-issued", TokenPurpose.PASSWORD_RESET))
                .isInstanceOf(InvalidTokenException.class);
        assertThatThrownBy(() -> store.consume(null, TokenPurpose.PASSWORD_RESET))
                .isInstanceOf(InvalidTokenException.class);
        assertThatThrownBy(() -> store.consume("  ", TokenPurpose.PASSWORD_RESET))
                .isInstanceOf(InvalidTokenException.class);
    }

    @Test
    void expired_token_is_rejected() {
        var clock = new MutableClock(FIXED);
        var store = new InMemorySingleUseTokenStore(clock);
        var token = store.issue("u-1", TokenPurpose.PASSWORD_RESET);

        // PASSWORD_RESET TTL is 1 hour; advance well beyond.
        clock.advance(Duration.ofHours(2));

        assertThatThrownBy(() -> store.consume(token.token(), TokenPurpose.PASSWORD_RESET))
                .isInstanceOf(InvalidTokenException.class)
                .hasMessageContaining("expired");
    }

    @Test
    void per_purpose_TTLs_are_distinct() {
        // INVITE_ACCEPT: 7 days. PASSWORD_RESET: 1 hour. The expiry math is
        // the assertion — proves the enum drives the TTL, not config drift.
        assertThat(TokenPurpose.INVITE_ACCEPT.ttl()).isEqualTo(Duration.ofDays(7));
        assertThat(TokenPurpose.PASSWORD_RESET.ttl()).isEqualTo(Duration.ofHours(1));
    }

    @Test
    void revoke_all_for_user_drops_every_token_for_that_user() {
        var store = new InMemorySingleUseTokenStore(Clock.fixed(FIXED, ZoneOffset.UTC));
        var invite = store.issue("u-1", TokenPurpose.INVITE_ACCEPT);
        var reset  = store.issue("u-1", TokenPurpose.PASSWORD_RESET);
        var other  = store.issue("u-2", TokenPurpose.PASSWORD_RESET);

        store.revokeAllForUser("u-1");

        assertThatThrownBy(() -> store.consume(invite.token(), TokenPurpose.INVITE_ACCEPT))
                .isInstanceOf(InvalidTokenException.class);
        assertThatThrownBy(() -> store.consume(reset.token(), TokenPurpose.PASSWORD_RESET))
                .isInstanceOf(InvalidTokenException.class);
        // Other user's token survives.
        assertThat(store.consume(other.token(), TokenPurpose.PASSWORD_RESET).userId())
                .isEqualTo("u-2");
    }

    @Test
    void issued_tokens_are_unique_and_url_safe() {
        var store = new InMemorySingleUseTokenStore(Clock.fixed(FIXED, ZoneOffset.UTC));
        var a = store.issue("u-1", TokenPurpose.INVITE_ACCEPT).token();
        var b = store.issue("u-1", TokenPurpose.INVITE_ACCEPT).token();

        assertThat(a).isNotEqualTo(b);
        assertThat(a).matches("[A-Za-z0-9_-]+");
    }

    private static final class MutableClock extends Clock {
        private Instant now;
        MutableClock(Instant start) { this.now = start; }
        void advance(Duration d) { now = now.plus(d); }
        @Override public Instant instant() { return now; }
        @Override public java.time.ZoneId getZone() { return ZoneOffset.UTC; }
        @Override public Clock withZone(java.time.ZoneId zone) { return this; }
    }
}
