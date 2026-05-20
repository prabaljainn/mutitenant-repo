package com.orochiverse.platform.iam.auth;

import static com.orochiverse.platform.testsupport.IT.bearer;
import static com.orochiverse.platform.testsupport.IT.url;
import static org.assertj.core.api.Assertions.assertThat;

import java.util.Map;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIf;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

import com.orochiverse.platform.common.security.passwords.PasswordHashing;
import com.orochiverse.platform.iam.operators.OperatorAssignment;
import com.orochiverse.platform.iam.operators.OperatorAssignmentRepository;
import com.orochiverse.platform.iam.tenants.Tenant;
import com.orochiverse.platform.iam.tenants.TenantRepository;
import com.orochiverse.platform.iam.users.UserRepository;
import com.orochiverse.platform.testsupport.IamFixtures;
import com.orochiverse.platform.testsupport.MongoTestSupport;

/**
 * End-to-end auth flow against the real Mongo dev stack: HTTP → controller
 * → service → real repositories. Each test seeds its own user with a
 * random suffix so concurrent runs and CI parallelism don't collide.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@ActiveProfiles("integration")
@EnabledIf("com.orochiverse.platform.testsupport.MongoTestSupport#mongoIsReachable")
class AuthApiIT {

    @DynamicPropertySource
    static void mongoProps(DynamicPropertyRegistry registry) {
        MongoTestSupport.mongoProps(registry);
    }

    @LocalServerPort int port;
    @Autowired UserRepository users;
    @Autowired TenantRepository tenants;
    @Autowired OperatorAssignmentRepository assignments;
    @Autowired PasswordHashing passwords;

    private String suffix;
    private String operatorId;
    private String operatorEmail;
    private String tenantId;
    private final String password = IamFixtures.DEFAULT_PASSWORD;

    @BeforeEach
    void seed() {
        suffix = IamFixtures.randomSuffix();
        tenantId = "p17a" + suffix;
        var operator = IamFixtures.operator(suffix).save(users, passwords);
        operatorId = operator.id();
        operatorEmail = operator.email();
    }

    @AfterEach
    void cleanup() {
        users.deleteById(operatorId);
        tenants.deleteById(tenantId);
        assignments.findAllByOperatorUserId(operatorId)
                .forEach(a -> assignments.deleteById(a.id()));
    }

    // ─────────────────────────────────────────────────────────────────────
    // /login
    // ─────────────────────────────────────────────────────────────────────

    @Test
    @SuppressWarnings("unchecked")
    void login_returns_access_and_refresh_tokens() {
        var resp = post("/api/auth/login",
                Map.of("email", operatorEmail, "password", password), Map.class);

        assertThat(resp.getStatusCode()).isEqualTo(HttpStatus.OK);
        var body = resp.getBody();
        assertThat(body).containsKey("accessToken");
        assertThat(body).containsKey("refreshToken");
        assertThat(body).containsEntry("tokenType", "Bearer");
        assertThat(body).containsEntry("expiresIn", 900);
    }

    @Test
    @SuppressWarnings("unchecked")
    void login_with_wrong_password_returns_401_with_generic_error() {
        var resp = post("/api/auth/login",
                Map.of("email", operatorEmail, "password", "wrong"), Map.class);

        assertThat(resp.getStatusCode()).isEqualTo(HttpStatus.UNAUTHORIZED);
        assertThat(resp.getBody()).containsEntry("error", "invalid_credentials");
    }

    @Test
    @SuppressWarnings("unchecked")
    void login_with_unknown_email_returns_401_with_same_error_shape() {
        var resp = post("/api/auth/login",
                Map.of("email", "ghost-" + suffix + "@example.com", "password", "anything"),
                Map.class);

        assertThat(resp.getStatusCode()).isEqualTo(HttpStatus.UNAUTHORIZED);
        assertThat(resp.getBody()).containsEntry("error", "invalid_credentials");
    }

    @Test
    @SuppressWarnings("unchecked")
    void login_validates_request_body() {
        var resp = post("/api/auth/login",
                Map.of("email", "not-an-email", "password", ""), Map.class);

        assertThat(resp.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
        assertThat(resp.getBody()).containsEntry("error", "validation_failed");
    }

    // ─────────────────────────────────────────────────────────────────────
    // /refresh (rotation)
    // ─────────────────────────────────────────────────────────────────────

    @Test
    @SuppressWarnings("unchecked")
    void refresh_rotates_the_refresh_token_and_invalidates_the_old() {
        var first = post("/api/auth/login",
                Map.of("email", operatorEmail, "password", password), Map.class).getBody();
        String oldRefresh = (String) first.get("refreshToken");

        var refreshed = post("/api/auth/refresh",
                Map.of("refreshToken", oldRefresh), Map.class);
        assertThat(refreshed.getStatusCode()).isEqualTo(HttpStatus.OK);

        var newBody = refreshed.getBody();
        assertThat(newBody).containsKey("accessToken");
        assertThat(newBody.get("refreshToken")).isNotEqualTo(oldRefresh);

        // Old refresh token is now dead — second call must fail.
        var replayed = post("/api/auth/refresh",
                Map.of("refreshToken", oldRefresh), Map.class);
        assertThat(replayed.getStatusCode()).isEqualTo(HttpStatus.UNAUTHORIZED);
        assertThat(replayed.getBody()).containsEntry("error", "invalid_refresh_token");
    }

    @Test
    @SuppressWarnings("unchecked")
    void refresh_with_unknown_token_returns_401() {
        var resp = post("/api/auth/refresh",
                Map.of("refreshToken", "this-was-never-issued"), Map.class);

        assertThat(resp.getStatusCode()).isEqualTo(HttpStatus.UNAUTHORIZED);
    }

    // ─────────────────────────────────────────────────────────────────────
    // /logout
    // ─────────────────────────────────────────────────────────────────────

    @Test
    @SuppressWarnings("unchecked")
    void logout_revokes_the_refresh_token() {
        var first = post("/api/auth/login",
                Map.of("email", operatorEmail, "password", password), Map.class).getBody();
        String access = (String) first.get("accessToken");
        String refresh = (String) first.get("refreshToken");

        var resp = postWithAuth("/api/auth/logout", Map.of("refreshToken", refresh), access, Void.class);
        assertThat(resp.getStatusCode()).isEqualTo(HttpStatus.NO_CONTENT);

        // The refresh token is gone.
        var replay = post("/api/auth/refresh", Map.of("refreshToken", refresh), Map.class);
        assertThat(replay.getStatusCode()).isEqualTo(HttpStatus.UNAUTHORIZED);
    }

    @Test
    void logout_with_no_body_is_204() {
        var first = post("/api/auth/login",
                Map.of("email", operatorEmail, "password", password), Map.class).getBody();
        String access = (String) first.get("accessToken");

        var headers = bearer(access);
        var resp = new TestRestTemplate().exchange(
                url(port, "/api/auth/logout"), HttpMethod.POST, new HttpEntity<>(headers), Void.class);

        assertThat(resp.getStatusCode()).isEqualTo(HttpStatus.NO_CONTENT);
    }

    @Test
    void logout_is_public_so_a_stale_tab_can_still_revoke_its_session() {
        // /logout authenticates itself via the refresh token in the body —
        // same model as /refresh. Requiring a valid access token would mean
        // the SPA can't revoke a session after its access token has expired
        // or after it has wiped local credentials, which is exactly when
        // the user is clicking "Sign out". Keeping this public is what
        // prevents the active-sessions table from accumulating one row per
        // logout. See SecurityConfig.
        var resp = new TestRestTemplate().postForEntity(
                url(port, "/api/auth/logout"), null, Map.class);
        assertThat(resp.getStatusCode()).isEqualTo(HttpStatus.NO_CONTENT);
    }

    @Test
    void logout_revokes_without_an_access_token() {
        var first = post("/api/auth/login",
                Map.of("email", operatorEmail, "password", password), Map.class).getBody();
        String refresh = (String) first.get("refreshToken");

        // No Authorization header — just the refresh token in the body, which
        // is the realistic shape of the call the SPA makes after clearing
        // session storage.
        var resp = post("/api/auth/logout", Map.of("refreshToken", refresh), Void.class);
        assertThat(resp.getStatusCode()).isEqualTo(HttpStatus.NO_CONTENT);

        var replay = post("/api/auth/refresh", Map.of("refreshToken", refresh), Map.class);
        assertThat(replay.getStatusCode()).isEqualTo(HttpStatus.UNAUTHORIZED);
    }

    // ─────────────────────────────────────────────────────────────────────
    // /switch-tenant
    // ─────────────────────────────────────────────────────────────────────

    @Test
    @SuppressWarnings("unchecked")
    void switch_tenant_succeeds_for_assigned_operator_and_new_token_carries_tid() {
        tenants.save(Tenant.create(tenantId, "Acme " + suffix, operatorId));
        assignments.save(OperatorAssignment.grant(operatorId, tenantId, operatorId));

        var login = post("/api/auth/login",
                Map.of("email", operatorEmail, "password", password), Map.class).getBody();
        String access = (String) login.get("accessToken");

        var resp = postWithAuth("/api/auth/switch-tenant",
                Map.of("tenantId", tenantId), access, Map.class);

        assertThat(resp.getStatusCode()).isEqualTo(HttpStatus.OK);
        var body = resp.getBody();
        assertThat(body).containsKey("accessToken");
        assertThat(body).containsEntry("tokenType", "Bearer");

        // Use the new access token against /me — it should report tid bound.
        var meHeaders = bearer((String) body.get("accessToken"));
        var me = new TestRestTemplate().exchange(
                url(port, "/api/auth/me"), HttpMethod.GET, new HttpEntity<>(meHeaders), Map.class);
        assertThat(me.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(me.getBody()).containsEntry("activeTenantId", tenantId);
        assertThat(me.getBody()).containsEntry("tenantContextBound", true);
    }

    @Test
    @SuppressWarnings("unchecked")
    void switch_tenant_returns_403_for_unassigned_operator() {
        // No assignment created for tenantId on purpose.
        tenants.save(Tenant.create(tenantId, "Acme " + suffix, operatorId));

        var login = post("/api/auth/login",
                Map.of("email", operatorEmail, "password", password), Map.class).getBody();
        String access = (String) login.get("accessToken");

        var resp = postWithAuth("/api/auth/switch-tenant",
                Map.of("tenantId", tenantId), access, Map.class);

        assertThat(resp.getStatusCode()).isEqualTo(HttpStatus.FORBIDDEN);
        assertThat(resp.getBody()).containsEntry("error", "operator_not_assigned");
    }

    // ─────────────────────────────────────────────────────────────────────
    // Helpers
    // ─────────────────────────────────────────────────────────────────────

    private <T> org.springframework.http.ResponseEntity<T> post(String path, Object body, Class<T> type) {
        return new TestRestTemplate().postForEntity(url(port, path), body, type);
    }

    private <T> org.springframework.http.ResponseEntity<T> postWithAuth(String path, Object body, String token, Class<T> type) {
        var headers = bearer(token);
        return new TestRestTemplate().exchange(
                url(port, path), HttpMethod.POST, new HttpEntity<>(body, headers), type);
    }
}
