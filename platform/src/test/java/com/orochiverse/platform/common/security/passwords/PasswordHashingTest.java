package com.orochiverse.platform.common.security.passwords;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import org.junit.jupiter.api.Test;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;

class PasswordHashingTest {

    private final PasswordHashing hashing = new PasswordHashing(new BCryptPasswordEncoder(PasswordHashing.BCRYPT_COST));

    @Test
    void hash_then_match_succeeds() {
        var stored = hashing.hash("correct horse battery staple");
        assertThat(hashing.matches("correct horse battery staple", stored)).isTrue();
    }

    @Test
    void wrong_password_does_not_match() {
        var stored = hashing.hash("correct horse battery staple");
        assertThat(hashing.matches("wrong password", stored)).isFalse();
    }

    @Test
    void hashes_of_the_same_password_differ() {
        // BCrypt embeds a fresh random salt on every encode — proves it.
        var a = hashing.hash("hunter2");
        var b = hashing.hash("hunter2");
        assertThat(a).isNotEqualTo(b);
        assertThat(hashing.matches("hunter2", a)).isTrue();
        assertThat(hashing.matches("hunter2", b)).isTrue();
    }

    @Test
    void hash_uses_cost_12() {
        // Stored format is `$2a$<cost>$...`. Anything below 12 means the
        // BCRYPT_COST constant got lowered without an ADR.
        var stored = hashing.hash("anything");
        assertThat(stored).startsWith("$2a$12$");
    }

    @Test
    void blank_password_is_rejected_at_hash_time() {
        assertThatThrownBy(() -> hashing.hash("  ")).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> hashing.hash(null)).isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void match_returns_false_for_blank_or_null_inputs() {
        var stored = hashing.hash("anything");
        assertThat(hashing.matches(null, stored)).isFalse();
        assertThat(hashing.matches("anything", null)).isFalse();
        assertThat(hashing.matches("anything", " ")).isFalse();
    }
}
