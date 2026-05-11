package com.orochiverse.platform.iam.auth;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Optional;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import com.orochiverse.platform.common.audit.AuditAction;
import com.orochiverse.platform.common.audit.AuditEntry;
import com.orochiverse.platform.common.audit.AuditEntryRepository;
import com.orochiverse.platform.common.security.jwt.AccessTokenIssuer;
import com.orochiverse.platform.common.security.jwt.JwtProperties;
import com.orochiverse.platform.common.security.passwords.PasswordHashing;
import com.orochiverse.platform.common.security.principals.OperatorRole;
import com.orochiverse.platform.common.security.principals.UserKind;
import com.orochiverse.platform.iam.operators.OperatorAssignmentRepository;
import com.orochiverse.platform.iam.users.User;
import com.orochiverse.platform.iam.users.UserRepository;
import com.orochiverse.platform.iam.users.UserStatus;

class AuthServiceTest {

    private UserRepository users;
    private OperatorAssignmentRepository assignments;
    private RefreshTokenStore refreshTokens;
    private AccessTokenIssuer issuer;
    private PasswordHashing passwords;
    private AuditEntryRepository audit;
    private AuthService service;

    @BeforeEach
    void setUp() {
        users = mock(UserRepository.class);
        assignments = mock(OperatorAssignmentRepository.class);
        refreshTokens = mock(RefreshTokenStore.class);
        issuer = mock(AccessTokenIssuer.class);
        passwords = mock(PasswordHashing.class);
        audit = mock(AuditEntryRepository.class);

        var jwtProps = new JwtProperties(
                "https://iam.test", Duration.ofMinutes(15), Duration.ofSeconds(30),
                null, null, null);
        service = new AuthService(users, assignments, refreshTokens, issuer, passwords, audit, jwtProps);
    }

    // ─────────────────────────────────────────────────────────────────────
    // login()
    // ─────────────────────────────────────────────────────────────────────

    @Test
    void login_succeeds_with_correct_credentials() {
        var op = activeOperator("op-1", "op@x.example", OperatorRole.OPERATOR_ADMIN);
        when(users.findByEmailIgnoreCase("op@x.example")).thenReturn(Optional.of(op));
        when(passwords.matches("hunter2", op.passwordHash())).thenReturn(true);
        when(issuer.issue(eq("op-1"), eq("op@x.example"), eq(UserKind.OPERATOR),
                eq(OperatorRole.OPERATOR_ADMIN), eq(null), eq(null), eq(0)))
                .thenReturn(new AccessTokenIssuer.Issued("the-access-token", null));
        when(refreshTokens.issue("op-1"))
                .thenReturn(new RefreshToken("the-refresh-token", "op-1",
                        Instant.now(), Instant.now().plus(Duration.ofDays(30))));

        var resp = service.login("op@x.example", "hunter2");

        assertThat(resp.accessToken()).isEqualTo("the-access-token");
        assertThat(resp.refreshToken()).isEqualTo("the-refresh-token");
        assertThat(resp.tokenType()).isEqualTo("Bearer");
        assertThat(resp.expiresIn()).isEqualTo(900);

        ArgumentCaptor<AuditEntry> entry = ArgumentCaptor.forClass(AuditEntry.class);
        verify(audit).save(entry.capture());
        assertThat(entry.getValue().action()).isEqualTo(AuditAction.LOGIN_SUCCESS);
        assertThat(entry.getValue().actorUserId()).isEqualTo("op-1");
    }

    @Test
    void login_rejects_unknown_email_and_audits_failure() {
        when(users.findByEmailIgnoreCase("ghost@x.example")).thenReturn(Optional.empty());

        assertThatThrownBy(() -> service.login("ghost@x.example", "anything"))
                .isInstanceOf(InvalidCredentialsException.class);

        ArgumentCaptor<AuditEntry> entry = ArgumentCaptor.forClass(AuditEntry.class);
        verify(audit).save(entry.capture());
        assertThat(entry.getValue().action()).isEqualTo(AuditAction.LOGIN_FAILURE);
        assertThat(entry.getValue().metadata()).containsEntry("email", "ghost@x.example");

        verify(refreshTokens, never()).issue(any());
        verify(issuer, never()).issue(any(), any(), any(), any(), any(), any(), eq(0));
    }

    @Test
    void login_rejects_wrong_password() {
        var op = activeOperator("op-1", "op@x.example", OperatorRole.OPERATOR_ADMIN);
        when(users.findByEmailIgnoreCase("op@x.example")).thenReturn(Optional.of(op));
        when(passwords.matches("wrong", op.passwordHash())).thenReturn(false);

        assertThatThrownBy(() -> service.login("op@x.example", "wrong"))
                .isInstanceOf(InvalidCredentialsException.class);

        verify(audit, times(1)).save(any());
        verify(refreshTokens, never()).issue(any());
    }

    @Test
    void login_rejects_suspended_user() {
        var op = activeOperator("op-1", "op@x.example", OperatorRole.OPERATOR_ADMIN);
        var suspended = new User(
                op.id(), op.email(), op.passwordHash(), op.firstName(), op.lastName(),
                UserStatus.SUSPENDED, op.kind(), op.operatorRole(),
                op.tenantId(), op.tenantRole(), op.tokenVersion(),
                op.lastLoginAt(), op.createdAt(), op.updatedAt());
        when(users.findByEmailIgnoreCase("op@x.example")).thenReturn(Optional.of(suspended));
        when(passwords.matches("hunter2", suspended.passwordHash())).thenReturn(true);

        assertThatThrownBy(() -> service.login("op@x.example", "hunter2"))
                .isInstanceOf(InvalidCredentialsException.class);

        verify(refreshTokens, never()).issue(any());
    }

    // ─────────────────────────────────────────────────────────────────────
    // refresh()
    // ─────────────────────────────────────────────────────────────────────

    @Test
    void refresh_rotates_the_token_and_issues_a_new_access_token() {
        var op = activeOperator("op-1", "op@x.example", OperatorRole.OPERATOR_ADMIN);
        when(refreshTokens.consume("old-rt"))
                .thenReturn(Optional.of(new RefreshToken("old-rt", "op-1",
                        Instant.now(), Instant.now().plus(Duration.ofDays(1)))));
        when(users.findById("op-1")).thenReturn(Optional.of(op));
        when(issuer.issue(eq("op-1"), eq("op@x.example"), eq(UserKind.OPERATOR),
                eq(OperatorRole.OPERATOR_ADMIN), eq(null), eq(null), eq(0)))
                .thenReturn(new AccessTokenIssuer.Issued("new-access", null));
        when(refreshTokens.issue("op-1"))
                .thenReturn(new RefreshToken("new-rt", "op-1",
                        Instant.now(), Instant.now().plus(Duration.ofDays(30))));

        var resp = service.refresh("old-rt");

        assertThat(resp.accessToken()).isEqualTo("new-access");
        assertThat(resp.refreshToken()).isEqualTo("new-rt");
        verify(refreshTokens).consume("old-rt");  // old one removed
        verify(refreshTokens).issue("op-1");      // fresh one minted
    }

    @Test
    void refresh_rejects_unknown_or_expired_token() {
        when(refreshTokens.consume("rotten")).thenReturn(Optional.empty());

        assertThatThrownBy(() -> service.refresh("rotten"))
                .isInstanceOf(InvalidRefreshTokenException.class);

        verify(users, never()).findById(any());
        verify(issuer, never()).issue(any(), any(), any(), any(), any(), any(), eq(0));
    }

    @Test
    void refresh_rejects_when_user_is_no_longer_active() {
        var suspended = suspendedOperator("op-1", "op@x.example");
        when(refreshTokens.consume("old-rt"))
                .thenReturn(Optional.of(new RefreshToken("old-rt", "op-1",
                        Instant.now(), Instant.now().plus(Duration.ofDays(1)))));
        when(users.findById("op-1")).thenReturn(Optional.of(suspended));

        assertThatThrownBy(() -> service.refresh("old-rt"))
                .isInstanceOf(InvalidRefreshTokenException.class);

        verify(refreshTokens, never()).issue(any());
    }

    // ─────────────────────────────────────────────────────────────────────
    // logout()
    // ─────────────────────────────────────────────────────────────────────

    @Test
    void logout_revokes_the_supplied_token_idempotently() {
        service.logout("some-token");
        verify(refreshTokens).revoke("some-token");
    }

    @Test
    void logout_with_null_or_blank_does_nothing() {
        service.logout(null);
        service.logout("  ");
        verify(refreshTokens, never()).revoke(any());
    }

    // ─────────────────────────────────────────────────────────────────────
    // switchTenant()
    // ─────────────────────────────────────────────────────────────────────

    @Test
    void switch_tenant_issues_access_with_tid_and_audits() {
        var op = activeOperator("op-1", "op@x.example", OperatorRole.OPERATOR_ADMIN);
        when(users.findById("op-1")).thenReturn(Optional.of(op));
        when(assignments.existsByOperatorUserIdAndTenantId("op-1", "acme")).thenReturn(true);
        when(issuer.issue(eq("op-1"), eq("op@x.example"), eq(UserKind.OPERATOR),
                eq(OperatorRole.OPERATOR_ADMIN), eq("acme"), eq(null), eq(0)))
                .thenReturn(new AccessTokenIssuer.Issued("access-with-tid", null));

        var resp = service.switchTenant("op-1", "acme");

        assertThat(resp.accessToken()).isEqualTo("access-with-tid");
        assertThat(resp.expiresIn()).isEqualTo(900);

        ArgumentCaptor<AuditEntry> entry = ArgumentCaptor.forClass(AuditEntry.class);
        verify(audit).save(entry.capture());
        assertThat(entry.getValue().action()).isEqualTo(AuditAction.TENANT_SWITCHED);
        assertThat(entry.getValue().metadata()).containsEntry("tenantId", "acme");
    }

    @Test
    void switch_tenant_rejects_unassigned_operator() {
        var op = activeOperator("op-1", "op@x.example", OperatorRole.OPERATOR_ADMIN);
        when(users.findById("op-1")).thenReturn(Optional.of(op));
        when(assignments.existsByOperatorUserIdAndTenantId("op-1", "vega")).thenReturn(false);

        assertThatThrownBy(() -> service.switchTenant("op-1", "vega"))
                .isInstanceOf(OperatorNotAssignedException.class);

        verify(issuer, never()).issue(any(), any(), any(), any(), any(), any(), eq(0));
        verify(audit, never()).save(any());
    }

    @Test
    void switch_tenant_rejects_non_operator() {
        var tenantUser = activeTenantUser("tu-1", "x@y.example");
        when(users.findById("tu-1")).thenReturn(Optional.of(tenantUser));

        assertThatThrownBy(() -> service.switchTenant("tu-1", "acme"))
                .isInstanceOf(OperatorNotAssignedException.class);
    }

    // ─────────────────────────────────────────────────────────────────────
    // Helpers
    // ─────────────────────────────────────────────────────────────────────

    private static User activeOperator(String id, String email, OperatorRole role) {
        Instant t = Clock.fixed(Instant.parse("2026-05-11T00:00:00Z"), ZoneOffset.UTC).instant();
        return new User(id, email, "hashed", "First", "Last",
                UserStatus.ACTIVE, UserKind.OPERATOR, role,
                null, null, 0, null, t, t);
    }

    private static User suspendedOperator(String id, String email) {
        Instant t = Clock.fixed(Instant.parse("2026-05-11T00:00:00Z"), ZoneOffset.UTC).instant();
        return new User(id, email, "hashed", "First", "Last",
                UserStatus.SUSPENDED, UserKind.OPERATOR, OperatorRole.OPERATOR_ADMIN,
                null, null, 0, null, t, t);
    }

    private static User activeTenantUser(String id, String email) {
        Instant t = Clock.fixed(Instant.parse("2026-05-11T00:00:00Z"), ZoneOffset.UTC).instant();
        return new User(id, email, "hashed", "First", "Last",
                UserStatus.ACTIVE, UserKind.TENANT_USER, null,
                "acme",
                com.orochiverse.platform.common.security.principals.TenantRole.ADMIN,
                0, null, t, t);
    }
}
