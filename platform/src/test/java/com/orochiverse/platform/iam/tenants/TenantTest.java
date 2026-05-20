package com.orochiverse.platform.iam.tenants;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import org.junit.jupiter.api.Test;

class TenantTest {

    @Test
    void create_factory_yields_a_live_ownerless_tenant() {
        var t = Tenant.create("acme", "Acme Corp", "operator-1");
        assertThat(t.id()).isEqualTo("acme");
        assertThat(t.name()).isEqualTo("Acme Corp");
        assertThat(t.createdBy()).isEqualTo("operator-1");
        assertThat(t.ownerUserId()).isNull();
        assertThat(t.deletedAt()).isNull();
        assertThat(t.settings()).isEmpty();
        assertThat(t.createdAt()).isEqualTo(t.updatedAt());
    }

    @Test
    void withOwner_returns_a_copy_with_owner_set() {
        var t = Tenant.create("acme", "Acme", "op").withOwner("tuser-1");
        assertThat(t.ownerUserId()).isEqualTo("tuser-1");
    }

    @Test
    void withDeleted_stamps_deletedAt() {
        var t = Tenant.create("acme", "Acme", "op").withDeleted();
        assertThat(t.deletedAt()).isNotNull();
    }

    @Test
    void invalid_id_is_rejected() {
        assertThatThrownBy(() -> Tenant.create("Bad Id", "Name", "u"))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void blank_name_is_rejected() {
        assertThatThrownBy(() -> Tenant.create("acme", "  ", "u"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("name");
    }
}
