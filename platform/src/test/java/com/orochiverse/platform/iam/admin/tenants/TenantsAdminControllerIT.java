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
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

import com.mongodb.client.MongoClient;

import com.orochiverse.platform.common.security.passwords.PasswordHashing;
import com.orochiverse.platform.common.security.principals.OperatorRole;
import com.orochiverse.platform.common.tenant.TenantId;
import com.orochiverse.platform.iam.admin.AdminItSupport;
import com.orochiverse.platform.iam.tenants.TenantRepository;
import com.orochiverse.platform.iam.tenants.TenantStatus;
import com.orochiverse.platform.iam.users.UserRepository;

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@ActiveProfiles("integration")
@EnabledIf("com.orochiverse.platform.iam.admin.AdminItSupport#mongoIsReachable")
class TenantsAdminControllerIT {

    @DynamicPropertySource
    static void mongoProps(DynamicPropertyRegistry r) {
        r.add("spring.data.mongodb.uri", () -> AdminItSupport.CONNECTION_URI);
    }

    @org.springframework.boot.test.web.server.LocalServerPort int port;
    @Autowired UserRepository users;
    @Autowired TenantRepository tenants;
    @Autowired PasswordHashing passwords;
    @Autowired MongoClient mongo;

    private String suffix;
    private String adminEmail;
    private String adminId;
    private String supportId;
    private final String adminPassword = "Sup3rSecret!";
    private final String supportPassword = "Sup3rSupport!";
    private String adminToken;
    private String supportToken;
    private String tenantId;

    @BeforeEach
    void setUp() {
        suffix = AdminItSupport.randomSuffix();
        adminEmail = "admin-" + suffix + "@orochi.example";

        adminId = AdminItSupport.seedOperator(users, passwords, "admin-" + suffix,
                adminEmail, adminPassword, OperatorRole.OPERATOR_ADMIN);
        supportId = AdminItSupport.seedOperator(users, passwords, "support-" + suffix,
                "support-" + suffix + "@orochi.example", supportPassword,
                OperatorRole.OPERATOR_SUPPORT);
        tenantId = "p17b" + suffix;

        adminToken = login(adminEmail, adminPassword);
        supportToken = login("support-" + suffix + "@orochi.example", supportPassword);
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
        var resp = AdminItSupport.exchange(url("/admin/api/tenants"), HttpMethod.POST, adminToken,
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
        AdminItSupport.exchange(url("/admin/api/tenants"), HttpMethod.POST, adminToken,
                Map.of("id", tenantId, "name", "Acme", "plan", "STARTER"), Map.class);

        var dup = AdminItSupport.exchange(url("/admin/api/tenants"), HttpMethod.POST, adminToken,
                Map.of("id", tenantId, "name", "Acme", "plan", "STARTER"), Map.class);

        assertThat(dup.getStatusCode()).isEqualTo(HttpStatus.CONFLICT);
        assertThat(dup.getBody()).containsEntry("error", "conflict");
    }

    @Test
    @SuppressWarnings("unchecked")
    void invalid_tenant_id_returns_400() {
        var resp = AdminItSupport.exchange(url("/admin/api/tenants"), HttpMethod.POST, adminToken,
                Map.of("id", "Bad Id With Spaces", "name", "Acme", "plan", "STARTER"), Map.class);

        assertThat(resp.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
    }

    @Test
    void support_cannot_create_a_tenant() {
        var resp = AdminItSupport.exchange(url("/admin/api/tenants"), HttpMethod.POST, supportToken,
                Map.of("id", tenantId, "name", "Acme", "plan", "STARTER"), Map.class);

        assertThat(resp.getStatusCode()).isEqualTo(HttpStatus.FORBIDDEN);
    }

    // ─────────────────────────────────────────────────────────────────────
    // List + get
    // ─────────────────────────────────────────────────────────────────────

    @Test
    void list_visible_to_support_role() {
        AdminItSupport.exchange(url("/admin/api/tenants"), HttpMethod.POST, adminToken,
                Map.of("id", tenantId, "name", "Acme", "plan", "STARTER"), Map.class);

        var resp = AdminItSupport.exchange(url("/admin/api/tenants?status=TRIAL"),
                HttpMethod.GET, supportToken, null, List.class);

        assertThat(resp.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(resp.getBody()).isNotEmpty();
    }

    @Test
    @SuppressWarnings("unchecked")
    void get_unknown_tenant_returns_404() {
        var resp = AdminItSupport.exchange(url("/admin/api/tenants/does-not-exist"),
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
        AdminItSupport.exchange(url("/admin/api/tenants"), HttpMethod.POST, adminToken,
                Map.of("id", tenantId, "name", "Acme", "plan", "STARTER"), Map.class);

        var resp = AdminItSupport.exchange(url("/admin/api/tenants/" + tenantId),
                HttpMethod.PUT, adminToken,
                Map.of("name", "Acme Renamed", "plan", "ENTERPRISE"), Map.class);

        assertThat(resp.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(resp.getBody()).containsEntry("name", "Acme Renamed");
        assertThat(resp.getBody()).containsEntry("plan", "ENTERPRISE");
    }

    @Test
    void support_cannot_update() {
        AdminItSupport.exchange(url("/admin/api/tenants"), HttpMethod.POST, adminToken,
                Map.of("id", tenantId, "name", "Acme", "plan", "STARTER"), Map.class);

        var resp = AdminItSupport.exchange(url("/admin/api/tenants/" + tenantId),
                HttpMethod.PUT, supportToken, Map.of("name", "Hijacked"), Map.class);

        assertThat(resp.getStatusCode()).isEqualTo(HttpStatus.FORBIDDEN);
    }

    // ─────────────────────────────────────────────────────────────────────
    // Soft-delete
    // ─────────────────────────────────────────────────────────────────────

    @Test
    @SuppressWarnings("unchecked")
    void admin_can_soft_delete_and_db_is_dropped() {
        AdminItSupport.exchange(url("/admin/api/tenants"), HttpMethod.POST, adminToken,
                Map.of("id", tenantId, "name", "Acme", "plan", "STARTER"), Map.class);

        var del = AdminItSupport.exchange(url("/admin/api/tenants/" + tenantId),
                HttpMethod.DELETE, adminToken, null, Void.class);

        assertThat(del.getStatusCode()).isEqualTo(HttpStatus.NO_CONTENT);

        // Status flipped to ARCHIVED, DB gone.
        var t = tenants.findById(tenantId).orElseThrow();
        assertThat(t.status()).isEqualTo(TenantStatus.ARCHIVED);
        var dbNames = mongo.listDatabaseNames().into(new java.util.ArrayList<>());
        assertThat(dbNames).doesNotContain(TenantId.dbName(tenantId));
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
