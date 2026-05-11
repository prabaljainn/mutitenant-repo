package com.orochiverse.platform.iam.admin.operators;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIf;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

import com.orochiverse.platform.common.security.passwords.PasswordHashing;
import com.orochiverse.platform.common.security.principals.OperatorRole;
import com.orochiverse.platform.iam.admin.AdminItSupport;
import com.orochiverse.platform.iam.users.UserRepository;
import com.orochiverse.platform.iam.users.UserStatus;

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@ActiveProfiles("integration")
@EnabledIf("com.orochiverse.platform.iam.admin.AdminItSupport#mongoIsReachable")
class OperatorsAdminControllerIT {

    @DynamicPropertySource
    static void mongoProps(DynamicPropertyRegistry r) {
        r.add("spring.data.mongodb.uri", () -> AdminItSupport.CONNECTION_URI);
    }

    @LocalServerPort int port;
    @Autowired UserRepository users;
    @Autowired PasswordHashing passwords;

    private String suffix;
    private String adminId;
    private String adminToken;
    private String createdInviteId;

    @BeforeEach
    void setUp() {
        suffix = AdminItSupport.randomSuffix();
        adminId = AdminItSupport.seedOperator(users, passwords, "admin-" + suffix,
                "admin-" + suffix + "@orochi.example", "Sup3rSecret!", OperatorRole.OPERATOR_ADMIN);
        adminToken = login("admin-" + suffix + "@orochi.example", "Sup3rSecret!");
    }

    @AfterEach
    void cleanup() {
        users.deleteById(adminId);
        if (createdInviteId != null) users.deleteById(createdInviteId);
    }

    // ─────────────────────────────────────────────────────────────────────
    // Invite
    // ─────────────────────────────────────────────────────────────────────

    @Test
    @SuppressWarnings("unchecked")
    void admin_can_invite_and_invitee_is_INVITED_with_no_password() {
        var resp = AdminItSupport.exchange(url("/admin/api/operators"), HttpMethod.POST, adminToken,
                Map.of("email", "newop-" + suffix + "@orochi.example",
                        "firstName", "New", "lastName", "Op", "role", "OPERATOR_SUPPORT"),
                Map.class);

        assertThat(resp.getStatusCode()).isEqualTo(HttpStatus.CREATED);
        Map<String, Object> body = resp.getBody();
        assertThat(body).containsEntry("status", "INVITED");
        assertThat(body).containsEntry("role", "OPERATOR_SUPPORT");

        createdInviteId = (String) body.get("id");
        // The invited user really exists in iam_db.
        var stored = users.findById(createdInviteId).orElseThrow();
        assertThat(stored.passwordHash()).isNull();
    }

    @Test
    @SuppressWarnings("unchecked")
    void duplicate_email_returns_409() {
        AdminItSupport.exchange(url("/admin/api/operators"), HttpMethod.POST, adminToken,
                Map.of("email", "dup-" + suffix + "@orochi.example",
                        "firstName", "A", "lastName", "B", "role", "OPERATOR_SUPPORT"),
                Map.class);

        var dup = AdminItSupport.exchange(url("/admin/api/operators"), HttpMethod.POST, adminToken,
                Map.of("email", "dup-" + suffix + "@orochi.example",
                        "firstName", "C", "lastName", "D", "role", "OPERATOR_ADMIN"),
                Map.class);

        assertThat(dup.getStatusCode()).isEqualTo(HttpStatus.CONFLICT);

        // Cleanup the first one we created
        users.findByEmailIgnoreCase("dup-" + suffix + "@orochi.example")
                .ifPresent(u -> users.deleteById(u.id()));
    }

    // ─────────────────────────────────────────────────────────────────────
    // List + get
    // ─────────────────────────────────────────────────────────────────────

    @Test
    void list_active_operators_includes_seeded_admin() {
        var resp = AdminItSupport.exchange(url("/admin/api/operators?status=ACTIVE"),
                HttpMethod.GET, adminToken, null, List.class);

        assertThat(resp.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(resp.getBody()).isNotEmpty();
    }

    @Test
    @SuppressWarnings("unchecked")
    void get_unknown_operator_returns_404() {
        var resp = AdminItSupport.exchange(url("/admin/api/operators/operator-deadbeef"),
                HttpMethod.GET, adminToken, null, Map.class);

        assertThat(resp.getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
    }

    // ─────────────────────────────────────────────────────────────────────
    // Update — role + suspend
    // ─────────────────────────────────────────────────────────────────────

    @Test
    @SuppressWarnings("unchecked")
    void admin_can_change_role_and_suspend() {
        var invite = AdminItSupport.exchange(url("/admin/api/operators"), HttpMethod.POST, adminToken,
                Map.of("email", "r-" + suffix + "@orochi.example",
                        "firstName", "R", "lastName", "Op", "role", "OPERATOR_SUPPORT"),
                Map.class);
        createdInviteId = (String) invite.getBody().get("id");

        var roleChange = AdminItSupport.exchange(url("/admin/api/operators/" + createdInviteId),
                HttpMethod.PUT, adminToken,
                Map.of("role", "OPERATOR_ADMIN"), Map.class);
        assertThat(roleChange.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(roleChange.getBody()).containsEntry("role", "OPERATOR_ADMIN");

        var suspend = AdminItSupport.exchange(url("/admin/api/operators/" + createdInviteId),
                HttpMethod.PUT, adminToken,
                Map.of("status", "SUSPENDED"), Map.class);
        assertThat(suspend.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(suspend.getBody()).containsEntry("status", "SUSPENDED");
    }

    @Test
    @SuppressWarnings("unchecked")
    void update_cannot_set_status_to_DELETED_directly() {
        var invite = AdminItSupport.exchange(url("/admin/api/operators"), HttpMethod.POST, adminToken,
                Map.of("email", "d-" + suffix + "@orochi.example",
                        "firstName", "D", "lastName", "Op", "role", "OPERATOR_SUPPORT"),
                Map.class);
        createdInviteId = (String) invite.getBody().get("id");

        var resp = AdminItSupport.exchange(url("/admin/api/operators/" + createdInviteId),
                HttpMethod.PUT, adminToken,
                Map.of("status", "DELETED"), Map.class);

        assertThat(resp.getStatusCode()).isEqualTo(HttpStatus.UNPROCESSABLE_ENTITY);
    }

    // ─────────────────────────────────────────────────────────────────────
    // Delete
    // ─────────────────────────────────────────────────────────────────────

    @Test
    @SuppressWarnings("unchecked")
    void admin_cannot_delete_themselves() {
        var resp = AdminItSupport.exchange(url("/admin/api/operators/" + adminId),
                HttpMethod.DELETE, adminToken, null, Map.class);

        assertThat(resp.getStatusCode()).isEqualTo(HttpStatus.UNPROCESSABLE_ENTITY);
    }

    @Test
    void admin_can_soft_delete_another_operator() {
        var invite = AdminItSupport.exchange(url("/admin/api/operators"), HttpMethod.POST, adminToken,
                Map.of("email", "del-" + suffix + "@orochi.example",
                        "firstName", "Del", "lastName", "Op", "role", "OPERATOR_SUPPORT"),
                Map.class);
        @SuppressWarnings("unchecked")
        String id = (String) ((Map<String, Object>) invite.getBody()).get("id");
        createdInviteId = id;

        var resp = AdminItSupport.exchange(url("/admin/api/operators/" + id),
                HttpMethod.DELETE, adminToken, null, Void.class);
        assertThat(resp.getStatusCode()).isEqualTo(HttpStatus.NO_CONTENT);

        var after = users.findById(id).orElseThrow();
        assertThat(after.status()).isEqualTo(UserStatus.DELETED);
    }

    // ─────────────────────────────────────────────────────────────────────
    // Helpers
    // ─────────────────────────────────────────────────────────────────────

    private String url(String path) {
        return "http://localhost:" + port + path;
    }

    @SuppressWarnings("unchecked")
    private String login(String email, String password) {
        var resp = new TestRestTemplate().postForEntity(
                url("/api/auth/login"), Map.of("email", email, "password", password), Map.class);
        assertThat(resp.getStatusCode()).isEqualTo(HttpStatus.OK);
        return (String) resp.getBody().get("accessToken");
    }
}
