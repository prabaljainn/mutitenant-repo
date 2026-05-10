package com.orochiverse.platform.iam.users;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import org.junit.jupiter.api.Test;

class UserTest {

    @Test
    void operator_factory_creates_a_valid_operator() {
        var u = User.newOperator("u1", "alice@orochi.example", "hash",
                "Alice", "Vega", OperatorRole.OPERATOR_ADMIN);
        assertThat(u.kind()).isEqualTo(UserKind.OPERATOR);
        assertThat(u.operatorRole()).isEqualTo(OperatorRole.OPERATOR_ADMIN);
        assertThat(u.tenantId()).isNull();
        assertThat(u.tenantRole()).isNull();
        assertThat(u.status()).isEqualTo(UserStatus.INVITED);
    }

    @Test
    void tenant_user_factory_creates_a_valid_tenant_user() {
        var u = User.newTenantUser("u2", "bob@acme.example", "hash",
                "Bob", "Doe", "acme", TenantRole.TENANT_OWNER);
        assertThat(u.kind()).isEqualTo(UserKind.TENANT_USER);
        assertThat(u.tenantId()).isEqualTo("acme");
        assertThat(u.tenantRole()).isEqualTo(TenantRole.TENANT_OWNER);
        assertThat(u.operatorRole()).isNull();
    }

    @Test
    void operator_without_role_is_rejected() {
        assertThatThrownBy(() -> new User(
                "u", "x@y", "h", "F", "L",
                UserStatus.ACTIVE, UserKind.OPERATOR, null,
                null, null, 0, null,
                java.time.Instant.now(), java.time.Instant.now()))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("operatorRole");
    }

    @Test
    void operator_with_tenant_fields_is_rejected() {
        assertThatThrownBy(() -> new User(
                "u", "x@y", "h", "F", "L",
                UserStatus.ACTIVE, UserKind.OPERATOR, OperatorRole.OPERATOR_ADMIN,
                "acme", TenantRole.ADMIN, 0, null,
                java.time.Instant.now(), java.time.Instant.now()))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("OPERATOR users must not have tenantId");
    }

    @Test
    void tenant_user_without_tenant_id_is_rejected() {
        assertThatThrownBy(() -> new User(
                "u", "x@y", "h", "F", "L",
                UserStatus.ACTIVE, UserKind.TENANT_USER, null,
                null, TenantRole.ADMIN, 0, null,
                java.time.Instant.now(), java.time.Instant.now()))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("tenantId");
    }

    @Test
    void tenant_user_with_operator_role_is_rejected() {
        assertThatThrownBy(() -> new User(
                "u", "x@y", "h", "F", "L",
                UserStatus.ACTIVE, UserKind.TENANT_USER, OperatorRole.OPERATOR_ADMIN,
                "acme", TenantRole.ADMIN, 0, null,
                java.time.Instant.now(), java.time.Instant.now()))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("operatorRole");
    }

    @Test
    void tenant_user_with_invalid_tenant_id_is_rejected() {
        assertThatThrownBy(() -> User.newTenantUser("u", "x@y", "h",
                "F", "L", "Bad Id", TenantRole.ADMIN))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void blank_email_is_rejected() {
        assertThatThrownBy(() -> User.newOperator("u", "  ", "h",
                "F", "L", OperatorRole.OPERATOR_ADMIN))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void canAccess_for_tenant_user_compares_tenant_id() {
        var u = User.newTenantUser("u", "a@b", "h", "A", "B", "acme", TenantRole.ADMIN);
        assertThat(u.canAccess("acme")).isTrue();
        assertThat(u.canAccess("vega")).isFalse();
    }

    @Test
    void canAccess_for_operator_throws() {
        var u = User.newOperator("u", "a@b", "h", "A", "B", OperatorRole.OPERATOR_ADMIN);
        assertThatThrownBy(() -> u.canAccess("acme"))
                .isInstanceOf(UnsupportedOperationException.class);
    }
}
