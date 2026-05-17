package com.orochiverse.platform.iam.auth;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIf;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

import com.orochiverse.platform.common.security.jwt.AccessTokenIssuer;
import com.orochiverse.platform.common.security.passwords.PasswordHashing;
import com.orochiverse.platform.iam.users.UserRepository;
import com.orochiverse.platform.testsupport.IT;
import com.orochiverse.platform.testsupport.IamFixtures;
import com.orochiverse.platform.testsupport.JwtTestSupport;
import com.orochiverse.platform.testsupport.MongoTestSupport;

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@ActiveProfiles("integration")
@EnabledIf("com.orochiverse.platform.testsupport.MongoTestSupport#mongoIsReachable")
class MeSelfServiceControllerIT {

    @DynamicPropertySource
    static void mongoProps(DynamicPropertyRegistry r) {
        MongoTestSupport.mongoProps(r);
    }

    @LocalServerPort int port;
    @Autowired UserRepository users;
    @Autowired PasswordHashing passwords;
    @Autowired AccessTokenIssuer issuer;
    @Autowired RefreshTokenStore refreshTokens;

    private String suffix;
    private String userId;
    private String token;

    @BeforeEach
    void setUp() {
        suffix = IamFixtures.randomSuffix();
        var user = IamFixtures.operator(suffix)
                .password(IamFixtures.DEFAULT_PASSWORD)
                .firstName("Self")
                .lastName("Service")
                .save(users, passwords);
        userId = user.id();
        token = JwtTestSupport.token(issuer, user);
    }

    @AfterEach
    void cleanup() {
        refreshTokens.revokeAllForUser(userId);
        users.deleteById(userId);
    }

    // ─────────────────────────────────────────────────────────────────────
    // Profile
    // ─────────────────────────────────────────────────────────────────────

    @Test
    @SuppressWarnings("unchecked")
    void update_profile_changes_first_and_last_name() {
        var resp = IT.exchange(port, "/api/auth/me/profile", HttpMethod.PATCH, token,
                Map.of("firstName", "Updated", "lastName", "Name"), Map.class);

        assertThat(resp.getStatusCode()).isEqualTo(HttpStatus.OK);
        Map<String, Object> body = resp.getBody();
        assertThat(body).containsEntry("firstName", "Updated");
        assertThat(body).containsEntry("lastName", "Name");

        var stored = users.findById(userId).orElseThrow();
        assertThat(stored.firstName()).isEqualTo("Updated");
        assertThat(stored.lastName()).isEqualTo("Name");
    }

    @Test
    @SuppressWarnings("unchecked")
    void update_profile_partial_change_leaves_other_field_alone() {
        var resp = IT.exchange(port, "/api/auth/me/profile", HttpMethod.PATCH, token,
                Map.of("firstName", "OnlyFirst"), Map.class);

        assertThat(resp.getStatusCode()).isEqualTo(HttpStatus.OK);
        var stored = users.findById(userId).orElseThrow();
        assertThat(stored.firstName()).isEqualTo("OnlyFirst");
        assertThat(stored.lastName()).isEqualTo("Service");
    }

    @Test
    void update_profile_requires_auth() {
        var resp = IT.exchange(port, "/api/auth/me/profile", HttpMethod.PATCH, /*token*/ null,
                Map.of("firstName", "X"), Map.class);
        assertThat(resp.getStatusCode()).isEqualTo(HttpStatus.UNAUTHORIZED);
    }

    // ─────────────────────────────────────────────────────────────────────
    // Password
    // ─────────────────────────────────────────────────────────────────────

    @Test
    @SuppressWarnings("unchecked")
    void change_password_succeeds_returns_fresh_tokens_and_revokes_others() {
        // Seed two pre-existing refresh tokens to prove they get nuked.
        var keep1 = refreshTokens.issue(userId);
        var keep2 = refreshTokens.issue(userId);
        assertThat(refreshTokens.listForUser(userId)).hasSize(2);

        String newPw = "Different" + IamFixtures.DEFAULT_PASSWORD;
        var resp = IT.exchange(port, "/api/auth/me/password", HttpMethod.POST, token,
                Map.of("currentPassword", IamFixtures.DEFAULT_PASSWORD,
                        "newPassword", newPw),
                Map.class);

        assertThat(resp.getStatusCode()).isEqualTo(HttpStatus.OK);
        Map<String, Object> body = resp.getBody();
        assertThat(body).containsKey("accessToken");
        assertThat(body).containsKey("refreshToken");

        // The new pair is the only outstanding session.
        var sessions = refreshTokens.listForUser(userId);
        assertThat(sessions).hasSize(1);
        assertThat(sessions.get(0).id())
                .isEqualTo(RefreshTokenStore.deriveSessionId((String) body.get("refreshToken")));

        // The old refresh tokens really are gone — consume() returns empty.
        assertThat(refreshTokens.consume(keep1.token())).isEmpty();
        assertThat(refreshTokens.consume(keep2.token())).isEmpty();

        // Hash on disk now matches the new password.
        var stored = users.findById(userId).orElseThrow();
        assertThat(passwords.matches(newPw, stored.passwordHash())).isTrue();
    }

    @Test
    @SuppressWarnings("unchecked")
    void change_password_with_wrong_current_returns_401() {
        var resp = IT.exchange(port, "/api/auth/me/password", HttpMethod.POST, token,
                Map.of("currentPassword", "definitely-wrong",
                        "newPassword", "NewPassword!1"),
                Map.class);

        assertThat(resp.getStatusCode()).isEqualTo(HttpStatus.UNAUTHORIZED);
    }

    @Test
    @SuppressWarnings("unchecked")
    void change_password_to_same_value_returns_422() {
        var resp = IT.exchange(port, "/api/auth/me/password", HttpMethod.POST, token,
                Map.of("currentPassword", IamFixtures.DEFAULT_PASSWORD,
                        "newPassword", IamFixtures.DEFAULT_PASSWORD),
                Map.class);

        assertThat(resp.getStatusCode()).isEqualTo(HttpStatus.UNPROCESSABLE_ENTITY);
    }

    @Test
    @SuppressWarnings("unchecked")
    void change_password_with_blank_fields_returns_400() {
        var resp = IT.exchange(port, "/api/auth/me/password", HttpMethod.POST, token,
                Map.of("currentPassword", "", "newPassword", ""), Map.class);

        assertThat(resp.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
    }

    // ─────────────────────────────────────────────────────────────────────
    // Sessions
    // ─────────────────────────────────────────────────────────────────────

    @Test
    @SuppressWarnings("unchecked")
    void sessions_list_returns_outstanding_refresh_tokens_newest_first() {
        var first = refreshTokens.issue(userId);
        // Tiny sleep so timestamps differ enough for sort stability across
        // clock granularity edge cases.
        try { Thread.sleep(5); } catch (InterruptedException ignored) { Thread.currentThread().interrupt(); }
        var second = refreshTokens.issue(userId);

        var resp = IT.exchange(port, "/api/auth/me/sessions",
                HttpMethod.GET, token, null, List.class);

        assertThat(resp.getStatusCode()).isEqualTo(HttpStatus.OK);
        var rows = (List<Map<String, Object>>) resp.getBody();
        assertThat(rows).hasSize(2);
        // Newest first
        assertThat(rows.get(0).get("id"))
                .isEqualTo(RefreshTokenStore.deriveSessionId(second.token()));
        assertThat(rows.get(1).get("id"))
                .isEqualTo(RefreshTokenStore.deriveSessionId(first.token()));
        // No raw token leakage in any row.
        assertThat(rows).noneMatch(r -> r.containsKey("token"));
    }

    @Test
    void sessions_revoke_known_id_returns_204_and_removes_session() {
        var rt = refreshTokens.issue(userId);
        String sid = RefreshTokenStore.deriveSessionId(rt.token());

        var resp = IT.exchange(port, "/api/auth/me/sessions/" + sid,
                HttpMethod.DELETE, token, null, Void.class);

        assertThat(resp.getStatusCode()).isEqualTo(HttpStatus.NO_CONTENT);
        assertThat(refreshTokens.consume(rt.token())).isEmpty();
    }

    @Test
    void sessions_revoke_unknown_id_is_idempotent_204() {
        var resp = IT.exchange(port, "/api/auth/me/sessions/0123456789abcdef",
                HttpMethod.DELETE, token, null, Void.class);

        assertThat(resp.getStatusCode()).isEqualTo(HttpStatus.NO_CONTENT);
    }

    @Test
    void sessions_list_requires_auth() {
        var resp = IT.exchange(port, "/api/auth/me/sessions",
                HttpMethod.GET, /*token*/ null, null, Map.class);
        assertThat(resp.getStatusCode()).isEqualTo(HttpStatus.UNAUTHORIZED);
    }
}
