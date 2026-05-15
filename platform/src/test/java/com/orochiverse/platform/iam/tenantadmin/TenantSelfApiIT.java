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
 * (cross-tenant reads return 404), role-based access (MEMBER 403 on
 * writes), and owner protection (the tenant's ownerUserId can't be
 * demoted or deleted).
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
    private String memberAId;
    private String ownerBId;
    private String createdInviteId;

    private String ownerAToken;
    private String adminAToken;
    private String memberAToken;
    private String ownerBToken;

    @BeforeEach
    void seed() {
        suffix = IamFixtures.randomSuffix();
        tenantA = "p18a" + suffix;
        tenantB = "p18b" + suffix;

        // Tenant A has an owner (auto-promoted at first invite in prod;
        // seeded here for the test). Tenant B has its own owner.
        var ownerA = IamFixtures.tenantUser("owner-a-" + suffix, tenantA)
                .email("owner-a-" + suffix + "@a.example")
                .role(TenantRole.ADMIN).save(users, passwords);
        var adminA = IamFixtures.tenantUser("admin-a-" + suffix, tenantA)
                .email("admin-a-" + suffix + "@a.example")
                .role(TenantRole.ADMIN).save(users, passwords);
        var memberA = IamFixtures.tenantUser("member-a-" + suffix, tenantA)
                .email("member-a-" + suffix + "@a.example")
                .role(TenantRole.MEMBER).save(users, passwords);
        var ownerB = IamFixtures.tenantUser("owner-b-" + suffix, tenantB)
                .email("owner-b-" + suffix + "@b.example")
                .role(TenantRole.ADMIN).save(users, passwords);

        tenants.save(Tenant.create(tenantA, "Acme " + suffix, "system").withOwner(ownerA.id()));
        tenants.save(Tenant.create(tenantB, "Vega " + suffix, "system").withOwner(ownerB.id()));

        ownerAId    = ownerA.id();
        ownerAEmail = ownerA.email();
        adminAId    = adminA.id();
        memberAId   = memberA.id();
        ownerBId    = ownerB.id();

        ownerAToken  = JwtTestSupport.token(issuer, ownerA);
        adminAToken  = JwtTestSupport.token(issuer, adminA);
        memberAToken = JwtTestSupport.token(issuer, memberA);
        ownerBToken  = JwtTestSupport.token(issuer, ownerB);
    }

    @AfterEach
    void cleanup() {
        users.deleteById(ownerAId);
        users.deleteById(adminAId);
        users.deleteById(memberAId);
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
        assertThat(user).containsEntry("role", "ADMIN");

        Map<String, Object> tenant = (Map<String, Object>) body.get("tenant");
        assertThat(tenant).containsEntry("id", tenantA);
        assertThat(tenant).containsEntry("ownerUserId", ownerAId);
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

        var ids = fromA.getBody().stream()
                .map(r -> ((Map<String, Object>) r).get("id"))
                .toList();
        assertThat(ids).containsExactlyInAnyOrder(ownerAId, adminAId, memberAId);
        assertThat(ids).doesNotContain(ownerBId);
    }

    @Test
    @SuppressWarnings("unchecked")
    void get_returns_404_for_user_in_a_different_tenant() {
        var resp = IT.exchange(port, "/api/tenant/users/" + ownerBId,
                HttpMethod.GET, ownerAToken, null, Map.class);

        assertThat(resp.getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
        assertThat(resp.getBody()).containsEntry("error", "not_found");
    }

    // ─────────────────────────────────────────────────────────────────────
    // Invite — RBAC
    // ─────────────────────────────────────────────────────────────────────

    @Test
    @SuppressWarnings("unchecked")
    void admin_can_invite_a_tenant_user() {
        var resp = IT.exchange(port, "/api/tenant/users", HttpMethod.POST, adminAToken,
                Map.of("email", "new-" + suffix + "@a.example",
                        "firstName", "New", "lastName", "User", "role", "MEMBER"),
                Map.class);

        assertThat(resp.getStatusCode()).isEqualTo(HttpStatus.CREATED);
        assertThat(resp.getBody()).containsEntry("status", "INVITED");
        assertThat(resp.getBody()).containsEntry("role", "MEMBER");

        createdInviteId = (String) resp.getBody().get("id");
        var stored = users.findById(createdInviteId).orElseThrow();
        assertThat(stored.tenantId()).isEqualTo(tenantA);
        assertThat(stored.passwordHash()).isNull();
    }

    @Test
    @SuppressWarnings("unchecked")
    void member_cannot_invite() {
        var resp = IT.exchange(port, "/api/tenant/users", HttpMethod.POST, memberAToken,
                Map.of("email", "x-" + suffix + "@a.example",
                        "firstName", "X", "lastName", "Y", "role", "MEMBER"),
                Map.class);

        assertThat(resp.getStatusCode()).isEqualTo(HttpStatus.FORBIDDEN);
    }

    // ─────────────────────────────────────────────────────────────────────
    // Update — RBAC + owner protection
    // ─────────────────────────────────────────────────────────────────────

    @Test
    @SuppressWarnings("unchecked")
    void admin_can_change_role_of_another_user() {
        var resp = IT.exchange(port, "/api/tenant/users/" + memberAId,
                HttpMethod.PUT, adminAToken,
                Map.of("role", "ADMIN"), Map.class);

        assertThat(resp.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(resp.getBody()).containsEntry("role", "ADMIN");
    }

    @Test
    @SuppressWarnings("unchecked")
    void cannot_demote_the_tenant_owner() {
        var resp = IT.exchange(port, "/api/tenant/users/" + ownerAId,
                HttpMethod.PUT, ownerAToken,
                Map.of("role", "MEMBER"), Map.class);

        assertThat(resp.getStatusCode()).isEqualTo(HttpStatus.UNPROCESSABLE_ENTITY);
    }

    @Test
    @SuppressWarnings("unchecked")
    void cannot_suspend_the_tenant_owner() {
        var resp = IT.exchange(port, "/api/tenant/users/" + ownerAId,
                HttpMethod.PUT, ownerAToken,
                Map.of("status", "SUSPENDED"), Map.class);

        assertThat(resp.getStatusCode()).isEqualTo(HttpStatus.UNPROCESSABLE_ENTITY);
    }

    @Test
    @SuppressWarnings("unchecked")
    void member_cannot_update() {
        var resp = IT.exchange(port, "/api/tenant/users/" + adminAId,
                HttpMethod.PUT, memberAToken,
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
    void cannot_delete_the_tenant_owner() {
        var resp = IT.exchange(port, "/api/tenant/users/" + ownerAId,
                HttpMethod.DELETE, adminAToken, null, Map.class);

        assertThat(resp.getStatusCode()).isEqualTo(HttpStatus.UNPROCESSABLE_ENTITY);
    }

    @Test
    @SuppressWarnings("unchecked")
    void cross_tenant_delete_returns_404() {
        var resp = IT.exchange(port, "/api/tenant/users/" + ownerBId,
                HttpMethod.DELETE, ownerAToken, null, Map.class);

        assertThat(resp.getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
        assertThat(users.findById(ownerBId)).isPresent();
    }

    @Test
    void admin_can_soft_delete_a_member() {
        var resp = IT.exchange(port, "/api/tenant/users/" + memberAId,
                HttpMethod.DELETE, adminAToken, null, Void.class);

        assertThat(resp.getStatusCode()).isEqualTo(HttpStatus.NO_CONTENT);

        var after = users.findById(memberAId).orElseThrow();
        assertThat(after.status()).isEqualTo(UserStatus.DELETED);
    }

    @Test
    @SuppressWarnings("unchecked")
    void deleting_a_member_frees_their_email_for_re_invite() {
        // Regression: previously the email stayed on the soft-deleted user
        // doc, so re-inviting the same address tripped the uniqueness check
        // (and the unique index) and 409'd.
        String memberEmail = "member-a-" + suffix + "@a.example";

        var del = IT.exchange(port, "/api/tenant/users/" + memberAId,
                HttpMethod.DELETE, adminAToken, null, Void.class);
        assertThat(del.getStatusCode()).isEqualTo(HttpStatus.NO_CONTENT);

        var reinvite = IT.exchange(port, "/api/tenant/users", HttpMethod.POST, adminAToken,
                Map.of("email", memberEmail,
                        "firstName", "Member", "lastName", "Again", "role", "MEMBER"),
                Map.class);

        assertThat(reinvite.getStatusCode()).isEqualTo(HttpStatus.CREATED);
        createdInviteId = (String) reinvite.getBody().get("id");
    }
}
