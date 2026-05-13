package com.orochiverse.platform.iam.admin.tenants;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIf;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

import com.mongodb.client.MongoClient;

import com.orochiverse.platform.common.security.jwt.AccessTokenIssuer;
import com.orochiverse.platform.common.security.passwords.PasswordHashing;
import com.orochiverse.platform.common.security.principals.OperatorRole;
import com.orochiverse.platform.common.tenant.TenantId;
import com.orochiverse.platform.iam.tenants.TenantRepository;
import com.orochiverse.platform.iam.tenants.TenantStatus;
import com.orochiverse.platform.iam.users.UserRepository;
import com.orochiverse.platform.testsupport.IT;
import com.orochiverse.platform.testsupport.IamFixtures;
import com.orochiverse.platform.testsupport.JwtTestSupport;
import com.orochiverse.platform.testsupport.MongoTestSupport;

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@ActiveProfiles("integration")
@EnabledIf("com.orochiverse.platform.testsupport.MongoTestSupport#mongoIsReachable")
class TenantsAdminControllerIT {

    @DynamicPropertySource
    static void mongoProps(DynamicPropertyRegistry r) {
        MongoTestSupport.mongoProps(r);
    }

    @org.springframework.boot.test.web.server.LocalServerPort int port;
    @Autowired UserRepository users;
    @Autowired TenantRepository tenants;
    @Autowired PasswordHashing passwords;
    @Autowired AccessTokenIssuer issuer;
    @Autowired MongoClient mongo;

    private String suffix;
    private String adminId;
    private String supportId;
    private String adminToken;
    private String supportToken;
    private String tenantId;

    @BeforeEach
    void setUp() {
        suffix = IamFixtures.randomSuffix();
        var admin = IamFixtures.operator(suffix)
                .id("admin-" + suffix)
                .email("admin-" + suffix + "@orochi.example")
                .role(OperatorRole.OPERATOR_ADMIN)
                .save(users, passwords);
        var support = IamFixtures.operator(suffix)
                .id("support-" + suffix)
                .email("support-" + suffix + "@orochi.example")
                .role(OperatorRole.OPERATOR_SUPPORT)
                .save(users, passwords);
        adminId = admin.id();
        supportId = support.id();
        adminToken = JwtTestSupport.token(issuer, admin);
        supportToken = JwtTestSupport.token(issuer, support);
        tenantId = "p17b" + suffix;
    }

    @AfterEach
    void cleanup() {
        users.deleteById(adminId);
        users.deleteById(supportId);
        tenants.deleteById(tenantId);
        mongo.getDatabase(TenantId.dbName(tenantId)).drop();
    }

    // ─────────────────────────────────────────────────────────────────────
    // Create
    // ─────────────────────────────────────────────────────────────────────

    @Test
    @SuppressWarnings("unchecked")
    void admin_can_create_a_tenant_and_db_is_provisioned() {
        var resp = IT.exchange(port, "/admin/api/tenants", HttpMethod.POST, adminToken,
                Map.of("id", tenantId, "name", "Acme " + suffix, "plan", "STARTER"), Map.class);

        assertThat(resp.getStatusCode()).isEqualTo(HttpStatus.CREATED);
        assertThat(resp.getBody()).containsEntry("id", tenantId);
        assertThat(resp.getBody()).containsEntry("status", "TRIAL");

        // Tenant DB exists in Mongo.
        var dbNames = mongo.listDatabaseNames().into(new java.util.ArrayList<>());
        assertThat(dbNames).contains(TenantId.dbName(tenantId));
    }

    @Test
    @SuppressWarnings("unchecked")
    void duplicate_create_returns_409() {
        IT.exchange(port, "/admin/api/tenants", HttpMethod.POST, adminToken,
                Map.of("id", tenantId, "name", "Acme", "plan", "STARTER"), Map.class);

        var dup = IT.exchange(port, "/admin/api/tenants", HttpMethod.POST, adminToken,
                Map.of("id", tenantId, "name", "Acme", "plan", "STARTER"), Map.class);

        assertThat(dup.getStatusCode()).isEqualTo(HttpStatus.CONFLICT);
        assertThat(dup.getBody()).containsEntry("error", "conflict");
    }

    @Test
    @SuppressWarnings("unchecked")
    void invalid_tenant_id_returns_400() {
        var resp = IT.exchange(port, "/admin/api/tenants", HttpMethod.POST, adminToken,
                Map.of("id", "Bad Id With Spaces", "name", "Acme", "plan", "STARTER"), Map.class);

        assertThat(resp.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
    }

    @Test
    void support_cannot_create_a_tenant() {
        var resp = IT.exchange(port, "/admin/api/tenants", HttpMethod.POST, supportToken,
                Map.of("id", tenantId, "name", "Acme", "plan", "STARTER"), Map.class);

        assertThat(resp.getStatusCode()).isEqualTo(HttpStatus.FORBIDDEN);
    }

    // ─────────────────────────────────────────────────────────────────────
    // List + get
    // ─────────────────────────────────────────────────────────────────────

    @Test
    void list_visible_to_support_role() {
        IT.exchange(port, "/admin/api/tenants", HttpMethod.POST, adminToken,
                Map.of("id", tenantId, "name", "Acme", "plan", "STARTER"), Map.class);

        var resp = IT.exchange(port, "/admin/api/tenants?status=TRIAL",
                HttpMethod.GET, supportToken, null, List.class);

        assertThat(resp.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(resp.getBody()).isNotEmpty();
    }

    @Test
    @SuppressWarnings("unchecked")
    void list_filters_by_q_substring_case_insensitive() {
        IT.exchange(port, "/admin/api/tenants", HttpMethod.POST, adminToken,
                Map.of("id", tenantId, "name", "Skyhawk-" + suffix, "plan", "STARTER"),
                Map.class);

        // Substring of the tenant name, lowercase — should still match.
        var hits = IT.exchange(port, "/admin/api/tenants?q=skyhawk",
                HttpMethod.GET, supportToken, null, List.class);
        assertThat(hits.getStatusCode()).isEqualTo(HttpStatus.OK);
        List<String> names = hits.getBody().stream()
                .map(r -> ((Map<String, Object>) r).get("name").toString())
                .toList();
        assertThat(names).anyMatch(n -> n.startsWith("Skyhawk-"));

        // No-match returns an empty list, not a 404.
        var misses = IT.exchange(port, "/admin/api/tenants?q=zzz-no-such-" + suffix,
                HttpMethod.GET, supportToken, null, List.class);
        assertThat(misses.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(misses.getBody()).isEmpty();
    }

    @Test
    @SuppressWarnings("unchecked")
    void get_unknown_tenant_returns_404() {
        var resp = IT.exchange(port, "/admin/api/tenants/does-not-exist",
                HttpMethod.GET, supportToken, null, Map.class);

        assertThat(resp.getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
        assertThat(resp.getBody()).containsEntry("error", "not_found");
    }

    // ─────────────────────────────────────────────────────────────────────
    // Update
    // ─────────────────────────────────────────────────────────────────────

    @Test
    @SuppressWarnings("unchecked")
    void admin_can_rename_and_change_plan() {
        IT.exchange(port, "/admin/api/tenants", HttpMethod.POST, adminToken,
                Map.of("id", tenantId, "name", "Acme", "plan", "STARTER"), Map.class);

        var resp = IT.exchange(port, "/admin/api/tenants/" + tenantId,
                HttpMethod.PUT, adminToken,
                Map.of("name", "Acme Renamed", "plan", "ENTERPRISE"), Map.class);

        assertThat(resp.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(resp.getBody()).containsEntry("name", "Acme Renamed");
        assertThat(resp.getBody()).containsEntry("plan", "ENTERPRISE");
    }

    @Test
    void support_cannot_update() {
        IT.exchange(port, "/admin/api/tenants", HttpMethod.POST, adminToken,
                Map.of("id", tenantId, "name", "Acme", "plan", "STARTER"), Map.class);

        var resp = IT.exchange(port, "/admin/api/tenants/" + tenantId,
                HttpMethod.PUT, supportToken, Map.of("name", "Hijacked"), Map.class);

        assertThat(resp.getStatusCode()).isEqualTo(HttpStatus.FORBIDDEN);
    }

    // ─────────────────────────────────────────────────────────────────────
    // Soft-delete
    // ─────────────────────────────────────────────────────────────────────

    @Test
    @SuppressWarnings("unchecked")
    void admin_can_soft_delete_and_db_is_dropped() {
        IT.exchange(port, "/admin/api/tenants", HttpMethod.POST, adminToken,
                Map.of("id", tenantId, "name", "Acme", "plan", "STARTER"), Map.class);

        var del = IT.exchange(port, "/admin/api/tenants/" + tenantId,
                HttpMethod.DELETE, adminToken, null, Void.class);

        assertThat(del.getStatusCode()).isEqualTo(HttpStatus.NO_CONTENT);

        // Status flipped to ARCHIVED, DB gone.
        var t = tenants.findById(tenantId).orElseThrow();
        assertThat(t.status()).isEqualTo(TenantStatus.ARCHIVED);
        var dbNames = mongo.listDatabaseNames().into(new java.util.ArrayList<>());
        assertThat(dbNames).doesNotContain(TenantId.dbName(tenantId));
    }

}
