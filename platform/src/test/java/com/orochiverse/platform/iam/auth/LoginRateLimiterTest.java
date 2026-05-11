package com.orochiverse.platform.iam.auth;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatNoException;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import org.junit.jupiter.api.Test;

class LoginRateLimiterTest {

    @Test
    void below_threshold_passes() {
        var limiter = new LoginRateLimiter();
        for (int i = 0; i < LoginRateLimiter.MAX_FAILED_ATTEMPTS - 1; i++) {
            limiter.recordFailure("alice@x", "1.2.3.4");
        }

        assertThatNoException().isThrownBy(() -> limiter.check("alice@x", "1.2.3.4"));
    }

    @Test
    void hits_threshold_locks_out_the_pair() {
        var limiter = new LoginRateLimiter();
        for (int i = 0; i < LoginRateLimiter.MAX_FAILED_ATTEMPTS; i++) {
            limiter.recordFailure("alice@x", "1.2.3.4");
        }

        assertThatThrownBy(() -> limiter.check("alice@x", "1.2.3.4"))
                .isInstanceOf(RateLimitExceededException.class);
    }

    @Test
    void same_email_from_a_different_ip_is_unaffected() {
        var limiter = new LoginRateLimiter();
        for (int i = 0; i < LoginRateLimiter.MAX_FAILED_ATTEMPTS; i++) {
            limiter.recordFailure("alice@x", "1.2.3.4");
        }

        assertThatNoException().isThrownBy(() -> limiter.check("alice@x", "5.6.7.8"));
    }

    @Test
    void successful_login_clears_the_counter() {
        var limiter = new LoginRateLimiter();
        for (int i = 0; i < LoginRateLimiter.MAX_FAILED_ATTEMPTS - 1; i++) {
            limiter.recordFailure("alice@x", "1.2.3.4");
        }

        limiter.recordSuccess("alice@x", "1.2.3.4");
        // Now even MAX more failures shouldn't trip — counter is back to zero.
        for (int i = 0; i < LoginRateLimiter.MAX_FAILED_ATTEMPTS - 1; i++) {
            limiter.recordFailure("alice@x", "1.2.3.4");
        }
        assertThatNoException().isThrownBy(() -> limiter.check("alice@x", "1.2.3.4"));
    }

    @Test
    void email_case_normalized_for_bucket_key() {
        var limiter = new LoginRateLimiter();
        for (int i = 0; i < LoginRateLimiter.MAX_FAILED_ATTEMPTS; i++) {
            limiter.recordFailure("Alice@x", "1.2.3.4");
        }

        // Lowercase variant should hit the same bucket and be locked out too.
        assertThatThrownBy(() -> limiter.check("alice@x", "1.2.3.4"))
                .isInstanceOf(RateLimitExceededException.class);
    }

    @Test
    void window_constant_matches_spec() {
        // 5 failures / 15 minutes per spec §8 — pinned in code so anyone
        // changing it has to update this assertion too.
        assertThat(LoginRateLimiter.MAX_FAILED_ATTEMPTS).isEqualTo(5);
        assertThat(LoginRateLimiter.WINDOW.toMinutes()).isEqualTo(15);
    }
}
