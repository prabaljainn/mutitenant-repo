package com.orochiverse.platform.iam.tenantadmin;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.time.Instant;
import java.util.List;
import java.util.Optional;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import com.orochiverse.platform.common.audit.AuditEntryRepository;
import com.orochiverse.platform.common.security.principals.TenantRole;
import com.orochiverse.platform.common.security.principals.UserKind;
import com.orochiverse.platform.common.tenant.MissingTenantContextException;
import com.orochiverse.platform.common.tenant.TenantContext;
import com.orochiverse.platform.iam.admin.common.AdminExceptions.ConflictException;
import com.orochiverse.platform.iam.admin.common.AdminExceptions.NotFoundException;
import com.orochiverse.platform.iam.admin.common.AdminExceptions.UnprocessableException;
import com.orochiverse.platform.iam.auth.RefreshTokenStore;
import com.orochiverse.platform.iam.tenantadmin.TenantSelfDtos.InviteTenantUserRequest;
import com.orochiverse.platform.iam.tenantadmin.TenantSelfDtos.UpdateTenantUserRequest;
import com.orochiverse.platform.iam.tenants.Tenant;
import com.orochiverse.platform.iam.users.User;
import com.orochiverse.platform.iam.users.UserRepository;
import com.orochiverse.platform.iam.users.UserStatus;

class TenantUsersServiceTest {

    private static final String TENANT = "acme";
    private static final String OTHER_TENANT = "vega";

    private UserRepository users;
    private com.orochiverse.platform.iam.tenants.TenantRepository tenants;
    private RefreshTokenStore refreshTokens;
    private com.orochiverse.platform.iam.tokens.SingleUseTokenStore singleUseTokens;
    private AuditEntryRepository audit;
    private com.orochiverse.platform.common.email.EmailService email;
    private TenantUsersService service;

    @BeforeEach
    void setUp() {
        users = mock(UserRepository.class);
        tenants = mock(com.orochiverse.platform.iam.tenants.TenantRepository.class);
        refreshTokens = mock(RefreshTokenStore.class);
        singleUseTokens = mock(com.orochiverse.platform.iam.tokens.SingleUseTokenStore.class);
        audit = mock(AuditEntryRepository.class);
        email = mock(com.orochiverse.platform.common.email.EmailService.class);
        var emailProps = new com.orochiverse.platform.common.email.EmailProperties(
                "noreply@test.local", null, "http://localhost:8080");
        when(singleUseTokens.issue(org.mockito.ArgumentMatchers.anyString(),
                org.mockito.ArgumentMatchers.eq(
                        com.orochiverse.platform.iam.tokens.TokenPurpose.INVITE_ACCEPT)))
                .thenAnswer(inv -> new com.orochiverse.platform.iam.tokens.SingleUseToken(
                        "stub-token", inv.getArgument(0),
                        com.orochiverse.platform.iam.tokens.TokenPurpose.INVITE_ACCEPT,
                        java.time.Instant.now(),
                        java.time.Instant.now().plus(java.time.Duration.ofDays(7))));
        var metrics = new com.orochiverse.platform.common.observability.AuthMetrics(
                new io.micrometer.core.instrument.simple.SimpleMeterRegistry());
        @SuppressWarnings("unchecked")
        org.springframework.beans.factory.ObjectProvider<
                com.orochiverse.platform.common.security.auth.TokenVersionLookup> tvProvider =
                mock(org.springframework.beans.factory.ObjectProvider.class);
        when(tvProvider.getIfAvailable()).thenReturn(null);
        service = new TenantUsersService(users, tenants, refreshTokens, singleUseTokens, audit,
                email, emailProps, metrics, tvProvider);

        // Default: tenant has no owner. Tests that need an owner override.
        when(tenants.findByIdAndDeletedAtIsNull(TENANT))
                .thenReturn(Optional.of(Tenant.create(TENANT, "Acme", "system")));
    }

    // ─────────────────────────────────────────────────────────────────────
    // Invite
    // ─────────────────────────────────────────────────────────────────────

    @Test
    void invite_creates_an_INVITED_tenant_user_in_current_tenant() {
        when(users.existsByEmailIgnoreCase("alice@acme.example")).thenReturn(false);
        when(users.save(any(User.class))).thenAnswer(inv -> inv.getArgument(0));

        var resp = TenantContext.callIn(TENANT, () -> service.invite(
                new InviteTenantUserRequest("alice@acme.example", "Alice", "X", TenantRole.ADMIN),
                "owner-1"));

        assertThat(resp.email()).isEqualTo("alice@acme.example");
        assertThat(resp.role()).isEqualTo(TenantRole.ADMIN);
        assertThat(resp.status()).isEqualTo(UserStatus.INVITED);

        var captor = org.mockito.ArgumentCaptor.forClass(User.class);
        verify(users).save(captor.capture());
        assertThat(captor.getValue().tenantId()).isEqualTo(TENANT);
        assertThat(captor.getValue().passwordHash()).isNull();
        assertThat(captor.getValue().kind()).isEqualTo(UserKind.TENANT_USER);
    }

    @Test
    void invite_auto_promotes_first_admin_to_tenant_owner() {
        when(users.existsByEmailIgnoreCase("first-admin@acme.example")).thenReturn(false);
        when(users.save(any(User.class))).thenAnswer(inv -> inv.getArgument(0));

        TenantContext.callIn(TENANT, () -> service.invite(
                new InviteTenantUserRequest("first-admin@acme.example", "First", "Admin",
                        TenantRole.ADMIN), "operator-1"));

        // The tenant should have been saved back with ownerUserId pointing at
        // the newly-created user.
        var tenantCaptor = org.mockito.ArgumentCaptor.forClass(Tenant.class);
        verify(tenants).save(tenantCaptor.capture());
        assertThat(tenantCaptor.getValue().ownerUserId()).isNotNull();
    }

    @Test
    void invite_does_not_auto_promote_when_tenant_already_has_owner() {
        when(tenants.findByIdAndDeletedAtIsNull(TENANT))
                .thenReturn(Optional.of(Tenant.create(TENANT, "Acme", "system").withOwner("preexisting")));
        when(users.existsByEmailIgnoreCase("second@acme.example")).thenReturn(false);
        when(users.save(any(User.class))).thenAnswer(inv -> inv.getArgument(0));

        TenantContext.callIn(TENANT, () -> service.invite(
                new InviteTenantUserRequest("second@acme.example", "Second", "Admin",
                        TenantRole.ADMIN), "operator-1"));

        // No tenant save — owner was already set, so auto-promote is a no-op.
        verify(tenants, never()).save(any(Tenant.class));
    }

    @Test
    void invite_does_not_auto_promote_when_role_is_MEMBER() {
        when(users.existsByEmailIgnoreCase("member@acme.example")).thenReturn(false);
        when(users.save(any(User.class))).thenAnswer(inv -> inv.getArgument(0));

        TenantContext.callIn(TENANT, () -> service.invite(
                new InviteTenantUserRequest("member@acme.example", "M", "U",
                        TenantRole.MEMBER), "operator-1"));

        // No auto-promote: MEMBER doesn't become owner even if tenant is ownerless.
        verify(tenants, never()).save(any(Tenant.class));
    }

    @Test
    void invite_rejects_duplicate_email() {
        when(users.existsByEmailIgnoreCase("dup@x.example")).thenReturn(true);

        assertThatThrownBy(() -> TenantContext.callIn(TENANT, () -> service.invite(
                new InviteTenantUserRequest("dup@x.example", "D", "U", TenantRole.MEMBER),
                "owner-1")))
                .isInstanceOf(ConflictException.class);
    }

    @Test
    void invite_throws_when_tenant_context_is_missing() {
        assertThatThrownBy(() -> service.invite(
                new InviteTenantUserRequest("alice@acme.example", "A", "B", TenantRole.ADMIN),
                "owner-1"))
                .isInstanceOf(MissingTenantContextException.class);
    }

    // ─────────────────────────────────────────────────────────────────────
    // Read — tenancy isolation
    // ─────────────────────────────────────────────────────────────────────

    @Test
    void list_only_returns_users_in_current_tenant() {
        when(users.findAllByTenantIdAndStatus(TENANT, UserStatus.ACTIVE))
                .thenReturn(List.of(activeAdmin("u1", TENANT)));

        var resp = TenantContext.callIn(TENANT, () -> service.list(null));

        assertThat(resp).hasSize(1);
        assertThat(resp.get(0).id()).isEqualTo("u1");
    }

    @Test
    void get_returns_404_for_user_in_a_different_tenant() {
        when(users.findById("u-other")).thenReturn(Optional.of(activeAdmin("u-other", OTHER_TENANT)));

        assertThatThrownBy(() -> TenantContext.callIn(TENANT, () -> service.get("u-other")))
                .isInstanceOf(NotFoundException.class);
    }

    @Test
    void get_returns_404_for_an_OPERATOR_id() {
        var op = new User("op-1", "op@orochi", "h", "Op", "User",
                UserStatus.ACTIVE, UserKind.OPERATOR,
                com.orochiverse.platform.common.security.principals.OperatorRole.OPERATOR_ADMIN,
                null, null, 0, null, Instant.now(), Instant.now());
        when(users.findById("op-1")).thenReturn(Optional.of(op));

        assertThatThrownBy(() -> TenantContext.callIn(TENANT, () -> service.get("op-1")))
                .isInstanceOf(NotFoundException.class);
    }

    // ─────────────────────────────────────────────────────────────────────
    // Update — owner protection
    // ─────────────────────────────────────────────────────────────────────

    @Test
    void update_cannot_set_status_to_DELETED() {
        when(users.findById("u1")).thenReturn(Optional.of(activeAdmin("u1", TENANT)));

        assertThatThrownBy(() -> TenantContext.callIn(TENANT, () -> service.update("u1",
                new UpdateTenantUserRequest(null, null, null, UserStatus.DELETED),
                "owner-1")))
                .isInstanceOf(UnprocessableException.class)
                .hasMessageContaining("DELETE");
    }

    @Test
    void update_cannot_demote_the_tenant_owner() {
        var owner = activeAdmin("owner-1", TENANT);
        when(users.findById("owner-1")).thenReturn(Optional.of(owner));
        when(tenants.findByIdAndDeletedAtIsNull(TENANT))
                .thenReturn(Optional.of(Tenant.create(TENANT, "Acme", "system").withOwner("owner-1")));

        assertThatThrownBy(() -> TenantContext.callIn(TENANT, () -> service.update("owner-1",
                new UpdateTenantUserRequest(null, null, TenantRole.MEMBER, null),
                "owner-1")))
                .isInstanceOf(UnprocessableException.class)
                .hasMessageContaining("tenant owner");

        verify(users, never()).save(any());
    }

    @Test
    void update_cannot_suspend_the_tenant_owner() {
        var owner = activeAdmin("owner-1", TENANT);
        when(users.findById("owner-1")).thenReturn(Optional.of(owner));
        when(tenants.findByIdAndDeletedAtIsNull(TENANT))
                .thenReturn(Optional.of(Tenant.create(TENANT, "Acme", "system").withOwner("owner-1")));

        assertThatThrownBy(() -> TenantContext.callIn(TENANT, () -> service.update("owner-1",
                new UpdateTenantUserRequest(null, null, null, UserStatus.SUSPENDED),
                "owner-1")))
                .isInstanceOf(UnprocessableException.class);

        verify(users, never()).save(any());
    }

    @Test
    void update_can_demote_a_non_owner_admin_to_member() {
        var admin = activeAdmin("admin-1", TENANT);
        when(users.findById("admin-1")).thenReturn(Optional.of(admin));
        when(tenants.findByIdAndDeletedAtIsNull(TENANT))
                .thenReturn(Optional.of(Tenant.create(TENANT, "Acme", "system").withOwner("owner-1")));
        when(users.save(any(User.class))).thenAnswer(inv -> inv.getArgument(0));

        var resp = TenantContext.callIn(TENANT, () -> service.update("admin-1",
                new UpdateTenantUserRequest(null, null, TenantRole.MEMBER, null),
                "owner-1"));

        assertThat(resp.role()).isEqualTo(TenantRole.MEMBER);
    }

    @Test
    void update_to_SUSPENDED_revokes_refresh_tokens() {
        when(users.findById("u1")).thenReturn(Optional.of(activeAdmin("u1", TENANT)));
        when(users.save(any(User.class))).thenAnswer(inv -> inv.getArgument(0));

        TenantContext.callIn(TENANT, () -> service.update("u1",
                new UpdateTenantUserRequest(null, null, null, UserStatus.SUSPENDED),
                "owner-1"));

        verify(refreshTokens).revokeAllForUser("u1");
    }

    @Test
    void update_to_SUSPENDED_bumps_tokenVersion_so_in_flight_access_tokens_are_rejected() {
        var u = activeAdmin("u1", TENANT);
        when(users.findById("u1")).thenReturn(Optional.of(u));
        when(users.save(any(User.class))).thenAnswer(inv -> inv.getArgument(0));

        TenantContext.callIn(TENANT, () -> service.update("u1",
                new UpdateTenantUserRequest(null, null, null, UserStatus.SUSPENDED),
                "owner-1"));

        var captor = org.mockito.ArgumentCaptor.forClass(User.class);
        verify(users).save(captor.capture());
        assertThat(captor.getValue().tokenVersion()).isEqualTo(1);
    }

    @Test
    void update_role_change_bumps_tokenVersion() {
        var u = activeAdmin("u1", TENANT);
        when(users.findById("u1")).thenReturn(Optional.of(u));
        when(users.save(any(User.class))).thenAnswer(inv -> inv.getArgument(0));

        TenantContext.callIn(TENANT, () -> service.update("u1",
                new UpdateTenantUserRequest(null, null, TenantRole.MEMBER, null),
                "owner-1"));

        var captor = org.mockito.ArgumentCaptor.forClass(User.class);
        verify(users).save(captor.capture());
        assertThat(captor.getValue().tokenVersion())
                .as("demoting ADMIN → MEMBER must bump tv so the old token's "
                        + "ADMIN authorities don't outlive the change")
                .isEqualTo(1);
    }

    @Test
    void update_cosmetic_edit_does_not_bump_tokenVersion() {
        var u = activeAdmin("u1", TENANT);
        when(users.findById("u1")).thenReturn(Optional.of(u));
        when(users.save(any(User.class))).thenAnswer(inv -> inv.getArgument(0));

        TenantContext.callIn(TENANT, () -> service.update("u1",
                new UpdateTenantUserRequest("NewFirst", null, null, null),
                "owner-1"));

        var captor = org.mockito.ArgumentCaptor.forClass(User.class);
        verify(users).save(captor.capture());
        assertThat(captor.getValue().tokenVersion()).isEqualTo(0);
    }

    // ─────────────────────────────────────────────────────────────────────
    // Soft-delete
    // ─────────────────────────────────────────────────────────────────────

    @Test
    void delete_blocks_self() {
        assertThatThrownBy(() -> TenantContext.runIn(TENANT,
                () -> service.softDelete("u1", "u1")))
                .isInstanceOf(UnprocessableException.class)
                .hasMessageContaining("themselves");
    }

    @Test
    void delete_blocks_tenant_owner() {
        var owner = activeAdmin("owner-1", TENANT);
        when(users.findById("owner-1")).thenReturn(Optional.of(owner));
        when(tenants.findByIdAndDeletedAtIsNull(TENANT))
                .thenReturn(Optional.of(Tenant.create(TENANT, "Acme", "system").withOwner("owner-1")));

        assertThatThrownBy(() -> TenantContext.runIn(TENANT,
                () -> service.softDelete("owner-1", "actor-1")))
                .isInstanceOf(UnprocessableException.class)
                .hasMessageContaining("tenant owner");
    }

    @Test
    void delete_marks_user_deleted_and_revokes_tokens_and_bumps_tv() {
        when(users.findById("u1")).thenReturn(Optional.of(activeAdmin("u1", TENANT)));
        when(users.save(any(User.class))).thenAnswer(inv -> inv.getArgument(0));

        TenantContext.callIn(TENANT, () -> {
            service.softDelete("u1", "owner-1");
            return null;
        });

        var captor = org.mockito.ArgumentCaptor.forClass(User.class);
        verify(users).save(captor.capture());
        assertThat(captor.getValue().status()).isEqualTo(UserStatus.DELETED);
        assertThat(captor.getValue().tokenVersion()).isEqualTo(1);
        verify(refreshTokens, times(1)).revokeAllForUser("u1");
    }

    @Test
    void delete_returns_404_for_user_in_a_different_tenant() {
        when(users.findById("u-other")).thenReturn(Optional.of(activeAdmin("u-other", OTHER_TENANT)));

        assertThatThrownBy(() -> TenantContext.runIn(TENANT,
                () -> service.softDelete("u-other", "owner-1")))
                .isInstanceOf(NotFoundException.class);

        verify(users, never()).save(any());
    }

    // ─────────────────────────────────────────────────────────────────────
    // Helpers
    // ─────────────────────────────────────────────────────────────────────

    private static User activeAdmin(String id, String tenantId) {
        return new User(id, id + "@" + tenantId + ".example", "h", "F", "L",
                UserStatus.ACTIVE, UserKind.TENANT_USER, null,
                tenantId, TenantRole.ADMIN, 0, null, Instant.now(), Instant.now());
    }
}
