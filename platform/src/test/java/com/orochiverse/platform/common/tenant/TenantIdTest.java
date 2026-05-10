package com.orochiverse.platform.common.tenant;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

class TenantIdTest {

    @ParameterizedTest
    @ValueSource(strings = {"acme", "vega7", "soft-bank", "tenant_42", "a", "0"})
    void accepts_valid_ids(String id) {
        assertThat(TenantId.requireValid(id)).isEqualTo(id);
    }

    @ParameterizedTest
    @ValueSource(
            strings = {
                "Acme", // uppercase
                "_leading", // leading underscore
                "-leading", // leading hyphen
                "has space",
                "has.dot",
                "has/slash",
                "has;semi",
                "has$dollar",
                "with..parent",
                ""
            })
    void rejects_invalid_ids(String id) {
        assertThatThrownBy(() -> TenantId.requireValid(id))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void rejects_null() {
        assertThatThrownBy(() -> TenantId.requireValid(null))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("null");
    }

    @Test
    void rejects_too_long() {
        var tooLong = "a".repeat(TenantId.MAX_LENGTH + 1);
        assertThatThrownBy(() -> TenantId.requireValid(tooLong))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void accepts_max_length() {
        var maxLen = "a".repeat(TenantId.MAX_LENGTH);
        assertThat(TenantId.requireValid(maxLen)).isEqualTo(maxLen);
    }

    @Test
    void db_name_format() {
        assertThat(TenantId.dbName("acme")).isEqualTo("tenant_acme_db");
        assertThat(TenantId.dbName("vega-7")).isEqualTo("tenant_vega-7_db");
    }

    @Test
    void db_name_validates_input() {
        assertThatThrownBy(() -> TenantId.dbName("Bad Id"))
                .isInstanceOf(IllegalArgumentException.class);
    }
}
