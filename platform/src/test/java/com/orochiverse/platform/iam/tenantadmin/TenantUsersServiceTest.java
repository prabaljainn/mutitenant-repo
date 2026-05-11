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
import com.orochiverse.platform.iam.users.User;
import com.orochiverse.platform.iam.users.UserRepository;
import com.orochiverse.platform.iam.users.UserStatus;

class TenantUsersServiceTest {

    private static final String TENANT = "acme";
    private static final String OTHER_TENANT = "vega";

    private UserRepository users;
    private RefreshTokenStore refreshTokens;
    private AuditEntryRepository audit;
    private TenantUsersService service;

    @BeforeEach
    void setUp() {
        users = mock(UserRepository.class);
        refreshTokens = mock(RefreshTokenStore.class);
        audit = mock(AuditEntryRepository.class);
        service = new TenantUsersService(users, refreshTokens, audit);
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

        // Verify the user that was actually saved had the right tenant + null password.
        var captor = org.mockito.ArgumentCaptor.forClass(User.class);
        verify(users).save(captor.capture());
        assertThat(captor.getValue().tenantId()).isEqualTo(TENANT);
        assertThat(captor.getValue().passwordHash()).isNull();
        assertThat(captor.getValue().kind()).isEqualTo(UserKind.TENANT_USER);
    }

    @Test
    void invite_rejects_TENANT_OWNER_role() {
        assertThatThrownBy(() -> TenantContext.callIn(TENANT, () -> service.invite(
                new InviteTenantUserRequest("o@o", "O", "W", TenantRole.TENANT_OWNER),
                "owner-1")))
                .isInstanceOf(UnprocessableException.class)
                .hasMessageContaining("ownership-transfer");

        verify(users, never()).save(any());
    }

    @Test
    void invite_rejects_duplicate_email() {
        when(users.existsByEmailIgnoreCase("dup@x.example")).thenReturn(true);

        assertThatThrownBy(() -> TenantContext.callIn(TENANT, () -> service.invite(
                new InviteTenantUserRequest("dup@x.example", "D", "U", TenantRole.EDITOR),
                "owner-1")))
                .isInstanceOf(ConflictException.class);
    }

    @Test
    void invite_throws_when_tenant_context_is_missing() {
        // No TenantContext.callIn around it — the service must refuse.
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
        // Even if some operator id collides, never expose them through this surface.
        var op = new User("op-1", "op@orochi", "h", "Op", "User",
                UserStatus.ACTIVE, UserKind.OPERATOR,
                com.orochiverse.platform.common.security.principals.OperatorRole.OPERATOR_ADMIN,
                null, null, 0, null, Instant.now(), Instant.now());
        when(users.findById("op-1")).thenReturn(Optional.of(op));

        assertThatThrownBy(() -> TenantContext.callIn(TENANT, () -> service.get("op-1")))
                .isInstanceOf(NotFoundException.class);
    }

    // ─────────────────────────────────────────────────────────────────────
    // Update — TENANT_OWNER invariants
    // ─────────────────────────────────────────────────────────────────────

    @Test
    void update_cannot_promote_to_TENANT_OWNER() {
        when(users.findById("u1")).thenReturn(Optional.of(activeAdmin("u1", TENANT)));

        assertThatThrownBy(() -> TenantContext.callIn(TENANT, () -> service.update("u1",
                new UpdateTenantUserRequest(null, null, TenantRole.TENANT_OWNER, null),
                "owner-1")))
                .isInstanceOf(UnprocessableException.class)
                .hasMessageContaining("TENANT_OWNER");
    }

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
    void update_cannot_demote_the_last_active_owner() {
        var owner = activeOwner("owner-1", TENANT);
        when(users.findById("owner-1")).thenReturn(Optional.of(owner));
        when(users.findAllByTenantIdAndStatus(TENANT, UserStatus.ACTIVE))
                .thenReturn(List.of(owner));

        assertThatThrownBy(() -> TenantContext.callIn(TENANT, () -> service.update("owner-1",
                new UpdateTenantUserRequest(null, null, TenantRole.ADMIN, null),
                "owner-1")))
                .isInstanceOf(UnprocessableException.class)
                .hasMessageContaining("last active TENANT_OWNER");

        verify(users, never()).save(any());
    }

    @Test
    void update_can_demote_an_owner_when_another_owner_exists() {
        var owner1 = activeOwner("owner-1", TENANT);
        var owner2 = activeOwner("owner-2", TENANT);
        when(users.findById("owner-1")).thenReturn(Optional.of(owner1));
        when(users.findAllByTenantIdAndStatus(TENANT, UserStatus.ACTIVE))
                .thenReturn(List.of(owner1, owner2));
        when(users.save(any(User.class))).thenAnswer(inv -> inv.getArgument(0));

        var resp = TenantContext.callIn(TENANT, () -> service.update("owner-1",
                new UpdateTenantUserRequest(null, null, TenantRole.ADMIN, null),
                "owner-2"));

        assertThat(resp.role()).isEqualTo(TenantRole.ADMIN);
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
    void delete_blocks_last_owner() {
        var owner = activeOwner("owner-1", TENANT);
        when(users.findById("owner-1")).thenReturn(Optional.of(owner));
        when(users.findAllByTenantIdAndStatus(TENANT, UserStatus.ACTIVE))
                .thenReturn(List.of(owner));

        assertThatThrownBy(() -> TenantContext.runIn(TENANT,
                () -> service.softDelete("owner-1", "owner-2")))
                .isInstanceOf(UnprocessableException.class);
    }

    @Test
    void delete_marks_user_deleted_and_revokes_tokens() {
        when(users.findById("u1")).thenReturn(Optional.of(activeAdmin("u1", TENANT)));
        when(users.save(any(User.class))).thenAnswer(inv -> inv.getArgument(0));

        TenantContext.callIn(TENANT, () -> {
            service.softDelete("u1", "owner-1");
            return null;
        });

        var captor = org.mockito.ArgumentCaptor.forClass(User.class);
        verify(users).save(captor.capture());
        assertThat(captor.getValue().status()).isEqualTo(UserStatus.DELETED);
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

    private static User activeOwner(String id, String tenantId) {
        return new User(id, id + "@" + tenantId + ".example", "h", "F", "L",
                UserStatus.ACTIVE, UserKind.TENANT_USER, null,
                tenantId, TenantRole.TENANT_OWNER, 0, null, Instant.now(), Instant.now());
    }
}
