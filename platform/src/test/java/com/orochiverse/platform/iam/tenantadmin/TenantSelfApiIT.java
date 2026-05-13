package com.orochiverse.platform.iam.tenantadmin;

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

import com.orochiverse.platform.common.security.jwt.AccessTokenIssuer;
import com.orochiverse.platform.common.security.passwords.PasswordHashing;
import com.orochiverse.platform.common.security.principals.TenantRole;
import com.orochiverse.platform.iam.tenants.Tenant;
import com.orochiverse.platform.iam.tenants.TenantRepository;
import com.orochiverse.platform.iam.users.UserRepository;
import com.orochiverse.platform.iam.users.UserStatus;
import com.orochiverse.platform.testsupport.IT;
import com.orochiverse.platform.testsupport.IamFixtures;
import com.orochiverse.platform.testsupport.JwtTestSupport;
import com.orochiverse.platform.testsupport.MongoTestSupport;

/**
 * End-to-end exercise of the {@code /api/tenant/*} surface against real
 * Mongo + the real {@code SecurityFilterChain}. Covers tenancy isolation
 * (cross-tenant reads return 404), role-based access (EDITOR/VIEWER 403
 * on writes), and the TENANT_OWNER invariants.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@ActiveProfiles("integration")
@EnabledIf("com.orochiverse.platform.testsupport.MongoTestSupport#mongoIsReachable")
class TenantSelfApiIT {

    @DynamicPropertySource
    static void mongoProps(DynamicPropertyRegistry r) {
        MongoTestSupport.mongoProps(r);
    }

    @LocalServerPort int port;
    @Autowired UserRepository users;
    @Autowired TenantRepository tenants;
    @Autowired PasswordHashing passwords;
    @Autowired AccessTokenIssuer issuer;

    private String suffix;
    private String tenantA;
    private String tenantB;
    private String ownerAId;
    private String ownerAEmail;
    private String adminAId;
    private String editorAId;
    private String ownerBId;
    private String createdInviteId;

    private String ownerAToken;
    private String adminAToken;
    private String editorAToken;
    private String ownerBToken;

    @BeforeEach
    void seed() {
        suffix = IamFixtures.randomSuffix();
        tenantA = "p18a" + suffix;
        tenantB = "p18b" + suffix;

        tenants.save(Tenant.newTrial(tenantA, "Acme " + suffix, "STARTER", "system"));
        tenants.save(Tenant.newTrial(tenantB, "Vega " + suffix, "STARTER", "system"));

        var ownerA = IamFixtures.tenantUser("owner-a-" + suffix, tenantA)
                .email("owner-a-" + suffix + "@a.example")
                .role(TenantRole.TENANT_OWNER).save(users, passwords);
        var adminA = IamFixtures.tenantUser("admin-a-" + suffix, tenantA)
                .email("admin-a-" + suffix + "@a.example")
                .role(TenantRole.ADMIN).save(users, passwords);
        var editorA = IamFixtures.tenantUser("editor-a-" + suffix, tenantA)
                .email("editor-a-" + suffix + "@a.example")
                .role(TenantRole.EDITOR).save(users, passwords);
        var ownerB = IamFixtures.tenantUser("owner-b-" + suffix, tenantB)
                .email("owner-b-" + suffix + "@b.example")
                .role(TenantRole.TENANT_OWNER).save(users, passwords);

        ownerAId    = ownerA.id();
        ownerAEmail = ownerA.email();
        adminAId    = adminA.id();
        editorAId   = editorA.id();
        ownerBId    = ownerB.id();

        ownerAToken  = JwtTestSupport.token(issuer, ownerA);
        adminAToken  = JwtTestSupport.token(issuer, adminA);
        editorAToken = JwtTestSupport.token(issuer, editorA);
        ownerBToken  = JwtTestSupport.token(issuer, ownerB);
    }

    @AfterEach
    void cleanup() {
        users.deleteById(ownerAId);
        users.deleteById(adminAId);
        users.deleteById(editorAId);
        users.deleteById(ownerBId);
        if (createdInviteId != null) users.deleteById(createdInviteId);
        tenants.deleteById(tenantA);
        tenants.deleteById(tenantB);
    }

    // ─────────────────────────────────────────────────────────────────────
    // /api/tenant/me
    // ─────────────────────────────────────────────────────────────────────

    @Test
    @SuppressWarnings("unchecked")
    void me_returns_user_and_tenant_for_tenant_user() {
        var resp = IT.exchange(port, "/api/tenant/me",
                HttpMethod.GET, ownerAToken, null, Map.class);

        assertThat(resp.getStatusCode()).isEqualTo(HttpStatus.OK);
        Map<String, Object> body = resp.getBody();

        Map<String, Object> user = (Map<String, Object>) body.get("user");
        assertThat(user).containsEntry("id", ownerAId);
        assertThat(user).containsEntry("email", ownerAEmail);
        assertThat(user).containsEntry("role", "TENANT_OWNER");

        Map<String, Object> tenant = (Map<String, Object>) body.get("tenant");
        assertThat(tenant).containsEntry("id", tenantA);
        assertThat(tenant).containsEntry("status", "TRIAL");
    }

    @Test
    void me_requires_authentication() {
        var resp = new TestRestTemplate().getForEntity(IT.url(port, "/api/tenant/me"), Map.class);
        assertThat(resp.getStatusCode()).isEqualTo(HttpStatus.UNAUTHORIZED);
    }

    // ─────────────────────────────────────────────────────────────────────
    // List + tenancy isolation
    // ─────────────────────────────────────────────────────────────────────

    @Test
    @SuppressWarnings("unchecked")
    void list_returns_only_users_in_callers_tenant() {
        var fromA = IT.exchange(port, "/api/tenant/users?status=ACTIVE",
                HttpMethod.GET, ownerAToken, null, List.class);
        assertThat(fromA.getStatusCode()).isEqualTo(HttpStatus.OK);

        // Tenant A has 3 active users: owner, admin, editor.
        var ids = fromA.getBody().stream()
                .map(r -> ((Map<String, Object>) r).get("id"))
                .toList();
        assertThat(ids).containsExactlyInAnyOrder(ownerAId, adminAId, editorAId);

        // None of tenant B's users should leak.
        assertThat(ids).doesNotContain(ownerBId);
    }

    @Test
    @SuppressWarnings("unchecked")
    void get_returns_404_for_user_in_a_different_tenant() {
        // ownerB exists in iam_db, but ownerA's tenant context shouldn't see them.
        var resp = IT.exchange(port, "/api/tenant/users/" + ownerBId,
                HttpMethod.GET, ownerAToken, null, Map.class);

        assertThat(resp.getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
        assertThat(resp.getBody()).containsEntry("error", "not_found");
    }

    // ─────────────────────────────────────────────────────────────────────
    // Invite — RBAC + tenancy
    // ─────────────────────────────────────────────────────────────────────

    @Test
    @SuppressWarnings("unchecked")
    void admin_can_invite_a_tenant_user() {
        var resp = IT.exchange(port, "/api/tenant/users", HttpMethod.POST, adminAToken,
                Map.of("email", "new-" + suffix + "@a.example",
                        "firstName", "New", "lastName", "User", "role", "EDITOR"),
                Map.class);

        assertThat(resp.getStatusCode()).isEqualTo(HttpStatus.CREATED);
        assertThat(resp.getBody()).containsEntry("status", "INVITED");
        assertThat(resp.getBody()).containsEntry("role", "EDITOR");

        createdInviteId = (String) resp.getBody().get("id");
        var stored = users.findById(createdInviteId).orElseThrow();
        assertThat(stored.tenantId()).isEqualTo(tenantA);
        assertThat(stored.passwordHash()).isNull();
    }

    @Test
    @SuppressWarnings("unchecked")
    void editor_cannot_invite() {
        var resp = IT.exchange(port, "/api/tenant/users", HttpMethod.POST, editorAToken,
                Map.of("email", "x-" + suffix + "@a.example",
                        "firstName", "X", "lastName", "Y", "role", "EDITOR"),
                Map.class);

        assertThat(resp.getStatusCode()).isEqualTo(HttpStatus.FORBIDDEN);
    }

    @Test
    @SuppressWarnings("unchecked")
    void invite_rejects_TENANT_OWNER_role() {
        var resp = IT.exchange(port, "/api/tenant/users", HttpMethod.POST, ownerAToken,
                Map.of("email", "o-" + suffix + "@a.example",
                        "firstName", "O", "lastName", "W", "role", "TENANT_OWNER"),
                Map.class);

        assertThat(resp.getStatusCode()).isEqualTo(HttpStatus.UNPROCESSABLE_ENTITY);
        assertThat(resp.getBody()).containsEntry("error", "unprocessable");
    }

    // ─────────────────────────────────────────────────────────────────────
    // Update — RBAC + owner protection
    // ─────────────────────────────────────────────────────────────────────

    @Test
    @SuppressWarnings("unchecked")
    void admin_can_change_role_of_another_user() {
        var resp = IT.exchange(port, "/api/tenant/users/" + editorAId,
                HttpMethod.PUT, adminAToken,
                Map.of("role", "VIEWER"), Map.class);

        assertThat(resp.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(resp.getBody()).containsEntry("role", "VIEWER");
    }

    @Test
    @SuppressWarnings("unchecked")
    void cannot_demote_the_only_owner() {
        var resp = IT.exchange(port, "/api/tenant/users/" + ownerAId,
                HttpMethod.PUT, ownerAToken,
                Map.of("role", "ADMIN"), Map.class);

        assertThat(resp.getStatusCode()).isEqualTo(HttpStatus.UNPROCESSABLE_ENTITY);
    }

    @Test
    @SuppressWarnings("unchecked")
    void update_rejects_promotion_to_TENANT_OWNER() {
        var resp = IT.exchange(port, "/api/tenant/users/" + adminAId,
                HttpMethod.PUT, ownerAToken,
                Map.of("role", "TENANT_OWNER"), Map.class);

        assertThat(resp.getStatusCode()).isEqualTo(HttpStatus.UNPROCESSABLE_ENTITY);
    }

    @Test
    @SuppressWarnings("unchecked")
    void editor_cannot_update() {
        var resp = IT.exchange(port, "/api/tenant/users/" + adminAId,
                HttpMethod.PUT, editorAToken,
                Map.of("firstName", "Hijack"), Map.class);

        assertThat(resp.getStatusCode()).isEqualTo(HttpStatus.FORBIDDEN);
    }

    // ─────────────────────────────────────────────────────────────────────
    // Delete
    // ─────────────────────────────────────────────────────────────────────

    @Test
    @SuppressWarnings("unchecked")
    void self_delete_is_blocked() {
        var resp = IT.exchange(port, "/api/tenant/users/" + ownerAId,
                HttpMethod.DELETE, ownerAToken, null, Map.class);

        assertThat(resp.getStatusCode()).isEqualTo(HttpStatus.UNPROCESSABLE_ENTITY);
    }

    @Test
    @SuppressWarnings("unchecked")
    void cross_tenant_delete_returns_404() {
        // ownerA tries to delete ownerB — should look like the user just doesn't exist.
        var resp = IT.exchange(port, "/api/tenant/users/" + ownerBId,
                HttpMethod.DELETE, ownerAToken, null, Map.class);

        assertThat(resp.getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);

        // ownerB still exists, untouched.
        assertThat(users.findById(ownerBId)).isPresent();
    }

    @Test
    void admin_can_soft_delete_an_editor() {
        var resp = IT.exchange(port, "/api/tenant/users/" + editorAId,
                HttpMethod.DELETE, adminAToken, null, Void.class);

        assertThat(resp.getStatusCode()).isEqualTo(HttpStatus.NO_CONTENT);

        var after = users.findById(editorAId).orElseThrow();
        assertThat(after.status()).isEqualTo(UserStatus.DELETED);
    }

}
