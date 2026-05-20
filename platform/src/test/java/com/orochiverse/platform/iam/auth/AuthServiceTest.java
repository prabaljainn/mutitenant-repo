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
    private com.orochiverse.platform.iam.tokens.SingleUseTokenStore singleUseTokens;
    private AccessTokenIssuer issuer;
    private PasswordHashing passwords;
    private AuditEntryRepository audit;
    private com.orochiverse.platform.common.email.EmailService email;
    private AuthService service;

    @BeforeEach
    void setUp() {
        users = mock(UserRepository.class);
        assignments = mock(OperatorAssignmentRepository.class);
        refreshTokens = mock(RefreshTokenStore.class);
        singleUseTokens = mock(com.orochiverse.platform.iam.tokens.SingleUseTokenStore.class);
        issuer = mock(AccessTokenIssuer.class);
        passwords = mock(PasswordHashing.class);
        audit = mock(AuditEntryRepository.class);
        email = mock(com.orochiverse.platform.common.email.EmailService.class);

        var jwtProps = new JwtProperties(
                "https://iam.test", Duration.ofMinutes(15), Duration.ofSeconds(30),
                null, null, null);
        var emailProps = new com.orochiverse.platform.common.email.EmailProperties(
                "noreply@test.local", null, "http://localhost:8080");
        var rateLimiter = new com.orochiverse.platform.iam.auth.LoginRateLimiter();
        @SuppressWarnings("unchecked")
        var tvProvider = (org.springframework.beans.factory.ObjectProvider<
                com.orochiverse.platform.common.security.auth.TokenVersionLookup>)
                mock(org.springframework.beans.factory.ObjectProvider.class);
        var metrics = new com.orochiverse.platform.common.observability.AuthMetrics(
                new io.micrometer.core.instrument.simple.SimpleMeterRegistry());
        service = new AuthService(users, assignments, refreshTokens, singleUseTokens, issuer,
                passwords, audit, email, emailProps, rateLimiter, tvProvider, metrics, jwtProps);
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
        when(refreshTokens.issue(eq("op-1"), any(), any(), any()))
                .thenReturn(new RefreshToken("the-refresh-token", "op-1",
                        Instant.now(), Instant.now().plus(Duration.ofDays(30))));

        var resp = service.login("op@x.example", "hunter2", "127.0.0.1", "test-agent");

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

        assertThatThrownBy(() -> service.login("ghost@x.example", "anything", "127.0.0.1", "test-agent"))
                .isInstanceOf(InvalidCredentialsException.class);

        ArgumentCaptor<AuditEntry> entry = ArgumentCaptor.forClass(AuditEntry.class);
        verify(audit).save(entry.capture());
        assertThat(entry.getValue().action()).isEqualTo(AuditAction.LOGIN_FAILURE);
        assertThat(entry.getValue().metadata()).containsEntry("email", "ghost@x.example");

        verify(refreshTokens, never()).issue(any(), any(), any(), any());
        verify(issuer, never()).issue(any(), any(), any(), any(), any(), any(), eq(0));
    }

    @Test
    void login_rejects_wrong_password() {
        var op = activeOperator("op-1", "op@x.example", OperatorRole.OPERATOR_ADMIN);
        when(users.findByEmailIgnoreCase("op@x.example")).thenReturn(Optional.of(op));
        when(passwords.matches("wrong", op.passwordHash())).thenReturn(false);

        assertThatThrownBy(() -> service.login("op@x.example", "wrong", "127.0.0.1", "test-agent"))
                .isInstanceOf(InvalidCredentialsException.class);

        verify(audit, times(1)).save(any());
        verify(refreshTokens, never()).issue(any(), any(), any(), any());
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

        assertThatThrownBy(() -> service.login("op@x.example", "hunter2", "127.0.0.1", "test-agent"))
                .isInstanceOf(InvalidCredentialsException.class);

        verify(refreshTokens, never()).issue(any(), any(), any(), any());
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
        when(refreshTokens.issue(eq("op-1"), any(), any(), any()))
                .thenReturn(new RefreshToken("new-rt", "op-1",
                        Instant.now(), Instant.now().plus(Duration.ofDays(30))));

        var resp = service.refresh("old-rt", "127.0.0.1", "test-agent");

        assertThat(resp.accessToken()).isEqualTo("new-access");
        assertThat(resp.refreshToken()).isEqualTo("new-rt");
        verify(refreshTokens).consume("old-rt");  // old one removed
        verify(refreshTokens).issue(eq("op-1"), any(), any(), any());      // fresh one minted
    }

    @Test
    void refresh_rejects_unknown_or_expired_token() {
        when(refreshTokens.consume("rotten")).thenReturn(Optional.empty());

        assertThatThrownBy(() -> service.refresh("rotten", "127.0.0.1", "test-agent"))
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

        assertThatThrownBy(() -> service.refresh("old-rt", "127.0.0.1", "test-agent"))
                .isInstanceOf(InvalidRefreshTokenException.class);

        verify(refreshTokens, never()).issue(any(), any(), any(), any());
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
    // Phase 1.9: forgot password
    // ─────────────────────────────────────────────────────────────────────

    @Test
    void forgot_password_silently_no_ops_for_unknown_email() {
        when(users.findByEmailIgnoreCase("ghost@x.example")).thenReturn(Optional.empty());

        service.requestPasswordReset("ghost@x.example");

        verify(singleUseTokens, never()).issue(any(), any());
        verify(email, never()).send(any(), any(), any(), any());
        verify(audit, never()).save(any());
    }

    @Test
    void forgot_password_silently_no_ops_for_suspended_user() {
        when(users.findByEmailIgnoreCase("op@x.example"))
                .thenReturn(Optional.of(suspendedOperator("op-1", "op@x.example")));

        service.requestPasswordReset("op@x.example");

        verify(singleUseTokens, never()).issue(any(), any());
        verify(email, never()).send(any(), any(), any(), any());
    }

    @Test
    void forgot_password_issues_token_emails_link_and_audits_for_active_user() {
        var op = activeOperator("op-1", "op@x.example", OperatorRole.OPERATOR_ADMIN);
        when(users.findByEmailIgnoreCase("op@x.example")).thenReturn(Optional.of(op));
        var token = new com.orochiverse.platform.iam.tokens.SingleUseToken(
                "reset-token", "op-1",
                com.orochiverse.platform.iam.tokens.TokenPurpose.PASSWORD_RESET,
                Instant.now(), Instant.now().plus(Duration.ofHours(1)));
        when(singleUseTokens.issue(eq("op-1"),
                eq(com.orochiverse.platform.iam.tokens.TokenPurpose.PASSWORD_RESET)))
                .thenReturn(token);

        service.requestPasswordReset("op@x.example");

        verify(email).send(eq("op@x.example"), eq("Reset your Orochiverse password"),
                eq("password-reset"), any());
        ArgumentCaptor<AuditEntry> entry = ArgumentCaptor.forClass(AuditEntry.class);
        verify(audit).save(entry.capture());
        assertThat(entry.getValue().action()).isEqualTo(AuditAction.PASSWORD_RESET_REQUESTED);
    }

    // ─────────────────────────────────────────────────────────────────────
    // Phase 1.9: reset password
    // ─────────────────────────────────────────────────────────────────────

    @Test
    void reset_password_with_invalid_token_throws() {
        when(singleUseTokens.consume(eq("rotten"),
                eq(com.orochiverse.platform.iam.tokens.TokenPurpose.PASSWORD_RESET)))
                .thenThrow(new com.orochiverse.platform.iam.tokens.InvalidTokenException("nope"));

        assertThatThrownBy(() -> service.resetPassword("rotten", "newPass!"))
                .isInstanceOf(com.orochiverse.platform.iam.tokens.InvalidTokenException.class);

        verify(users, never()).save(any());
    }

    @Test
    void reset_password_hashes_new_password_and_revokes_refresh_tokens() {
        var op = activeOperator("op-1", "op@x.example", OperatorRole.OPERATOR_ADMIN);
        when(singleUseTokens.consume(eq("good"),
                eq(com.orochiverse.platform.iam.tokens.TokenPurpose.PASSWORD_RESET)))
                .thenReturn(new com.orochiverse.platform.iam.tokens.SingleUseToken(
                        "good", "op-1",
                        com.orochiverse.platform.iam.tokens.TokenPurpose.PASSWORD_RESET,
                        Instant.now(), Instant.now().plus(Duration.ofMinutes(30))));
        when(users.findById("op-1")).thenReturn(Optional.of(op));
        when(passwords.hash("newPass!")).thenReturn("hashed-new");
        when(users.save(any(User.class))).thenAnswer(inv -> inv.getArgument(0));

        service.resetPassword("good", "newPass!");

        ArgumentCaptor<User> saved = ArgumentCaptor.forClass(User.class);
        verify(users).save(saved.capture());
        assertThat(saved.getValue().passwordHash()).isEqualTo("hashed-new");
        assertThat(saved.getValue().tokenVersion()).isEqualTo(op.tokenVersion() + 1);
        verify(refreshTokens).revokeAllForUser("op-1");

        ArgumentCaptor<AuditEntry> entry = ArgumentCaptor.forClass(AuditEntry.class);
        verify(audit).save(entry.capture());
        assertThat(entry.getValue().action()).isEqualTo(AuditAction.PASSWORD_RESET_COMPLETED);
    }

    @Test
    void reset_password_rejects_when_user_no_longer_active() {
        when(singleUseTokens.consume(eq("good"),
                eq(com.orochiverse.platform.iam.tokens.TokenPurpose.PASSWORD_RESET)))
                .thenReturn(new com.orochiverse.platform.iam.tokens.SingleUseToken(
                        "good", "op-1",
                        com.orochiverse.platform.iam.tokens.TokenPurpose.PASSWORD_RESET,
                        Instant.now(), Instant.now().plus(Duration.ofMinutes(30))));
        when(users.findById("op-1")).thenReturn(Optional.of(suspendedOperator("op-1", "x@x")));

        assertThatThrownBy(() -> service.resetPassword("good", "newPass!"))
                .isInstanceOf(com.orochiverse.platform.iam.tokens.InvalidTokenException.class);

        verify(users, never()).save(any());
    }

    // ─────────────────────────────────────────────────────────────────────
    // Phase 1.9: accept invite
    // ─────────────────────────────────────────────────────────────────────

    @Test
    void accept_invite_activates_user_and_returns_tokens() {
        // INVITED operator with no password yet.
        Instant t = Instant.parse("2026-05-11T00:00:00Z");
        var invited = new User("op-1", "op@x.example", null, "First", "Last",
                UserStatus.INVITED, UserKind.OPERATOR, OperatorRole.OPERATOR_ADMIN,
                null, null, 0, null, t, t);
        when(singleUseTokens.consume(eq("invite"),
                eq(com.orochiverse.platform.iam.tokens.TokenPurpose.INVITE_ACCEPT)))
                .thenReturn(new com.orochiverse.platform.iam.tokens.SingleUseToken(
                        "invite", "op-1",
                        com.orochiverse.platform.iam.tokens.TokenPurpose.INVITE_ACCEPT,
                        Instant.now(), Instant.now().plus(Duration.ofDays(1))));
        when(users.findById("op-1")).thenReturn(Optional.of(invited));
        when(passwords.hash("welcome!")).thenReturn("hashed-welcome");
        when(users.save(any(User.class))).thenAnswer(inv -> inv.getArgument(0));
        when(issuer.issue(any(), any(), any(), any(), any(), any(), org.mockito.ArgumentMatchers.anyInt()))
                .thenReturn(new com.orochiverse.platform.common.security.jwt.AccessTokenIssuer.Issued(
                        "access-jwt", null));
        when(refreshTokens.issue(eq("op-1"), any(), any(), any()))
                .thenReturn(new RefreshToken("rt", "op-1",
                        Instant.now(), Instant.now().plus(Duration.ofDays(30))));

        var resp = service.acceptInvite("invite", "welcome!", "127.0.0.1", "test-agent");

        assertThat(resp.accessToken()).isEqualTo("access-jwt");
        assertThat(resp.refreshToken()).isEqualTo("rt");

        ArgumentCaptor<User> saved = ArgumentCaptor.forClass(User.class);
        verify(users).save(saved.capture());
        assertThat(saved.getValue().status()).isEqualTo(UserStatus.ACTIVE);
        assertThat(saved.getValue().passwordHash()).isEqualTo("hashed-welcome");
    }

    @Test
    void accept_invite_rejects_already_active_user() {
        // Token re-use after the user was already activated some other way.
        var op = activeOperator("op-1", "op@x.example", OperatorRole.OPERATOR_ADMIN);
        when(singleUseTokens.consume(eq("invite"),
                eq(com.orochiverse.platform.iam.tokens.TokenPurpose.INVITE_ACCEPT)))
                .thenReturn(new com.orochiverse.platform.iam.tokens.SingleUseToken(
                        "invite", "op-1",
                        com.orochiverse.platform.iam.tokens.TokenPurpose.INVITE_ACCEPT,
                        Instant.now(), Instant.now().plus(Duration.ofDays(1))));
        when(users.findById("op-1")).thenReturn(Optional.of(op));

        assertThatThrownBy(() -> service.acceptInvite("invite", "welcome!", "127.0.0.1", "test-agent"))
                .isInstanceOf(com.orochiverse.platform.iam.tokens.InvalidTokenException.class);

        verify(users, never()).save(any());
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
