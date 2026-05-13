package com.orochiverse.platform.iam.tenants;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import org.junit.jupiter.api.Test;

class TenantTest {

    @Test
    void newTrial_creates_a_valid_tenant() {
        var t = Tenant.newTrial("acme", "Acme Corp", "STARTER", "operator-1");
        assertThat(t.id()).isEqualTo("acme");
        assertThat(t.name()).isEqualTo("Acme Corp");
        assertThat(t.status()).isEqualTo(TenantStatus.TRIAL);
        assertThat(t.plan()).isEqualTo("STARTER");
        assertThat(t.createdBy()).isEqualTo("operator-1");
        assertThat(t.createdAt()).isEqualTo(t.updatedAt());
        assertThat(t.settings()).isEmpty();
    }

    @Test
    void invalid_id_is_rejected() {
        assertThatThrownBy(() -> Tenant.newTrial("Bad Id", "Name", "P", "u"))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void blank_name_is_rejected() {
        assertThatThrownBy(() -> Tenant.newTrial("acme", "  ", "P", "u"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("name");
    }
}
