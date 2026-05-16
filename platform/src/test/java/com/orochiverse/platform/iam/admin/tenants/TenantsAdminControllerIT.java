package com.orochiverse.platform.iam.admin.tenants;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.ArrayList;
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
import com.orochiverse.platform.iam.operators.OperatorAssignment;
import com.orochiverse.platform.iam.operators.OperatorAssignmentRepository;
import com.orochiverse.platform.iam.tenants.TenantRepository;
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
    @Autowired OperatorAssignmentRepository assignments;
    @Autowired PasswordHashing passwords;
    @Autowired AccessTokenIssuer issuer;
    @Autowired MongoClient mongo;

    private String suffix;
    private String adminId;
    private String supportId;
    private String adminToken;
    private String supportToken;
    // Tracks every tenant created during a test so @AfterEach can clean
    // up the docs and per-tenant DBs even though the id is server-assigned.
    private final List<String> createdTenantIds = new ArrayList<>();

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
        createdTenantIds.clear();
    }

    @AfterEach
    void cleanup() {
        // Clean any assignments granted in the test before deleting users.
        assignments.findAllByOperatorUserId(supportId)
                .forEach(a -> assignments.deleteById(a.id()));
        users.deleteById(adminId);
        users.deleteById(supportId);
        for (String id : createdTenantIds) {
            tenants.deleteById(id);
            mongo.getDatabase(TenantId.dbName(id)).drop();
        }
    }

    /** Grants the test's SUPPORT operator visibility into a tenant. */
    private void assignSupportTo(String tenantId) {
        assignments.save(OperatorAssignment.grant(supportId, tenantId, adminId));
    }

    /** Creates a tenant via the API and records its server-assigned id. */
    @SuppressWarnings("unchecked")
    private String createTenant(String token, String name) {
        var resp = IT.exchange(port, "/admin/api/tenants", HttpMethod.POST, token,
                Map.of("name", name), Map.class);
        assertThat(resp.getStatusCode()).isEqualTo(HttpStatus.CREATED);
        String id = (String) resp.getBody().get("id");
        createdTenantIds.add(id);
        return id;
    }

    // ─────────────────────────────────────────────────────────────────────
    // Create
    // ─────────────────────────────────────────────────────────────────────

    @Test
    @SuppressWarnings("unchecked")
    void admin_can_create_a_tenant_and_db_is_provisioned() {
        var resp = IT.exchange(port, "/admin/api/tenants", HttpMethod.POST, adminToken,
                Map.of("name", "Acme " + suffix), Map.class);

        assertThat(resp.getStatusCode()).isEqualTo(HttpStatus.CREATED);
        String id = (String) resp.getBody().get("id");
        createdTenantIds.add(id);

        // Slug derived from name, lowercase + hyphenated, valid for Mongo.
        assertThat(id).matches("^[a-z0-9][a-z0-9-]*$");
        assertThat(id).startsWith("acme-");
        assertThat(resp.getBody()).containsEntry("ownerUserId", null);

        var dbNames = mongo.listDatabaseNames().into(new java.util.ArrayList<>());
        assertThat(dbNames).contains(TenantId.dbName(id));
    }

    @Test
    void duplicate_name_yields_distinct_ids() {
        // Same display name twice — server should slugify both, then
        // disambiguate the second with a random suffix instead of 409ing.
        String first = createTenant(adminToken, "Acme " + suffix);
        String second = createTenant(adminToken, "Acme " + suffix);

        assertThat(second).isNotEqualTo(first);
        // Both share the slug base; the second gets a -xxxx suffix.
        assertThat(first).startsWith("acme-");
        assertThat(second).startsWith(first + "-");
    }

    @Test
    @SuppressWarnings("unchecked")
    void blank_name_returns_400() {
        var resp = IT.exchange(port, "/admin/api/tenants", HttpMethod.POST, adminToken,
                Map.of("name", ""), Map.class);

        assertThat(resp.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
    }

    @Test
    @SuppressWarnings("unchecked")
    void support_cannot_create_a_tenant() {
        var resp = IT.exchange(port, "/admin/api/tenants", HttpMethod.POST, supportToken,
                Map.of("name", "Acme"), Map.class);

        assertThat(resp.getStatusCode()).isEqualTo(HttpStatus.FORBIDDEN);
    }

    // ─────────────────────────────────────────────────────────────────────
    // List + get
    // ─────────────────────────────────────────────────────────────────────

    @Test
    void list_visible_to_support_role_for_assigned_tenants() {
        String id = createTenant(adminToken, "Acme " + suffix);
        assignSupportTo(id);

        var resp = IT.exchange(port, "/admin/api/tenants",
                HttpMethod.GET, supportToken, null, List.class);

        assertThat(resp.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(resp.getBody()).isNotEmpty();
    }

    @Test
    @SuppressWarnings("unchecked")
    void list_filters_by_q_substring_case_insensitive() {
        String id = createTenant(adminToken, "Skyhawk-" + suffix);
        assignSupportTo(id);

        var hits = IT.exchange(port, "/admin/api/tenants?q=skyhawk",
                HttpMethod.GET, supportToken, null, List.class);
        assertThat(hits.getStatusCode()).isEqualTo(HttpStatus.OK);
        List<String> names = hits.getBody().stream()
                .map(r -> ((Map<String, Object>) r).get("name").toString())
                .toList();
        assertThat(names).anyMatch(n -> n.startsWith("Skyhawk-"));

        var misses = IT.exchange(port, "/admin/api/tenants?q=zzz-no-such-" + suffix,
                HttpMethod.GET, supportToken, null, List.class);
        assertThat(misses.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(misses.getBody()).isEmpty();
    }

    // ─────────────────────────────────────────────────────────────────────
    // SUPPORT visibility scoping (assignments-based)
    // ─────────────────────────────────────────────────────────────────────

    @Test
    @SuppressWarnings("unchecked")
    void support_without_assignment_sees_no_tenants_in_list() {
        // Admin creates a tenant. Support has no assignment to it.
        createTenant(adminToken, "Hidden " + suffix);

        var resp = IT.exchange(port, "/admin/api/tenants",
                HttpMethod.GET, supportToken, null, List.class);

        assertThat(resp.getStatusCode()).isEqualTo(HttpStatus.OK);
        // The created tenant must not surface for unassigned SUPPORT.
        // Other tests in parallel may have unrelated rows, so we check
        // that none belong to this suffix.
        List<String> names = (List<String>) resp.getBody().stream()
                .map(r -> ((Map<String, Object>) r).get("name").toString())
                .toList();
        assertThat(names).noneMatch(n -> n.contains(suffix));
    }

    @Test
    @SuppressWarnings("unchecked")
    void support_without_assignment_gets_404_on_tenant_get() {
        String id = createTenant(adminToken, "Hidden " + suffix);

        var resp = IT.exchange(port, "/admin/api/tenants/" + id,
                HttpMethod.GET, supportToken, null, Map.class);

        // 404, not 403 — defense-in-depth so SUPPORT can't enumerate ids.
        assertThat(resp.getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
        assertThat(resp.getBody()).containsEntry("error", "not_found");
    }

    @Test
    @SuppressWarnings("unchecked")
    void support_with_assignment_can_get_tenant() {
        String id = createTenant(adminToken, "Visible " + suffix);
        assignSupportTo(id);

        var resp = IT.exchange(port, "/admin/api/tenants/" + id,
                HttpMethod.GET, supportToken, null, Map.class);

        assertThat(resp.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(resp.getBody()).containsEntry("id", id);
    }

    @Test
    void admin_sees_every_tenant_regardless_of_assignments() {
        String id1 = createTenant(adminToken, "A " + suffix);
        String id2 = createTenant(adminToken, "B " + suffix);

        var resp = IT.exchange(port, "/admin/api/tenants",
                HttpMethod.GET, adminToken, null, List.class);

        assertThat(resp.getStatusCode()).isEqualTo(HttpStatus.OK);
        @SuppressWarnings("unchecked")
        List<String> ids = (List<String>) resp.getBody().stream()
                .map(r -> ((java.util.Map<String, Object>) r).get("id"))
                .toList();
        assertThat(ids).contains(id1, id2);
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
    void admin_can_rename_a_tenant() {
        String id = createTenant(adminToken, "Acme " + suffix);

        var resp = IT.exchange(port, "/admin/api/tenants/" + id,
                HttpMethod.PUT, adminToken,
                Map.of("name", "Acme Renamed"), Map.class);

        assertThat(resp.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(resp.getBody()).containsEntry("name", "Acme Renamed");
    }

    @Test
    void support_cannot_update() {
        String id = createTenant(adminToken, "Acme " + suffix);

        var resp = IT.exchange(port, "/admin/api/tenants/" + id,
                HttpMethod.PUT, supportToken, Map.of("name", "Hijacked"), Map.class);

        assertThat(resp.getStatusCode()).isEqualTo(HttpStatus.FORBIDDEN);
    }

    // ─────────────────────────────────────────────────────────────────────
    // Soft-delete
    // ─────────────────────────────────────────────────────────────────────

    @Test
    @SuppressWarnings("unchecked")
    void admin_can_soft_delete_and_db_is_dropped() {
        String id = createTenant(adminToken, "Acme " + suffix);

        var del = IT.exchange(port, "/admin/api/tenants/" + id,
                HttpMethod.DELETE, adminToken, null, Void.class);

        assertThat(del.getStatusCode()).isEqualTo(HttpStatus.NO_CONTENT);

        // deletedAt is stamped, DB gone, filtered findById sees nothing.
        var raw = tenants.findById(id).orElseThrow();
        assertThat(raw.deletedAt()).isNotNull();
        assertThat(tenants.findByIdAndDeletedAtIsNull(id)).isEmpty();
        var dbNames = mongo.listDatabaseNames().into(new java.util.ArrayList<>());
        assertThat(dbNames).doesNotContain(TenantId.dbName(id));
    }

    @Test
    @SuppressWarnings("unchecked")
    void soft_deleted_tenants_are_hidden_from_list_and_get() {
        String id = createTenant(adminToken, "Acme " + suffix);
        IT.exchange(port, "/admin/api/tenants/" + id,
                HttpMethod.DELETE, adminToken, null, Void.class);

        var get = IT.exchange(port, "/admin/api/tenants/" + id,
                HttpMethod.GET, supportToken, null, Map.class);
        assertThat(get.getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);

        var list = IT.exchange(port, "/admin/api/tenants",
                HttpMethod.GET, supportToken, null, List.class);
        assertThat(list.getStatusCode()).isEqualTo(HttpStatus.OK);
        List<String> ids = ((List<Map<String, Object>>) list.getBody()).stream()
                .map(r -> (String) r.get("id")).toList();
        assertThat(ids).doesNotContain(id);
    }
}
