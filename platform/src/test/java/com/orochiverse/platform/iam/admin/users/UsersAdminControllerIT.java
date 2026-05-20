package com.orochiverse.platform.iam.admin.users;

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
import com.orochiverse.platform.common.security.principals.OperatorRole;
import com.orochiverse.platform.iam.operators.OperatorAssignment;
import com.orochiverse.platform.iam.operators.OperatorAssignmentRepository;
import com.orochiverse.platform.iam.tenants.Tenant;
import com.orochiverse.platform.iam.tenants.TenantRepository;
import com.orochiverse.platform.iam.users.UserRepository;
import com.orochiverse.platform.testsupport.IT;
import com.orochiverse.platform.testsupport.IamFixtures;
import com.orochiverse.platform.testsupport.JwtTestSupport;
import com.orochiverse.platform.testsupport.MongoTestSupport;

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@ActiveProfiles("integration")
@EnabledIf("com.orochiverse.platform.testsupport.MongoTestSupport#mongoIsReachable")
class UsersAdminControllerIT {

    @DynamicPropertySource
    static void mongoProps(DynamicPropertyRegistry r) {
        MongoTestSupport.mongoProps(r);
    }

    @LocalServerPort int port;
    @Autowired UserRepository users;
    @Autowired TenantRepository tenants;
    @Autowired OperatorAssignmentRepository assignments;
    @Autowired PasswordHashing passwords;
    @Autowired AccessTokenIssuer issuer;

    private String suffix;
    private String adminId;
    private String adminToken;
    private String supportId;
    private String supportToken;
    private String assignedTenantId;
    private String otherTenantId;
    private String assignedUserId;
    private String otherUserId;

    @BeforeEach
    void setUp() {
        suffix = IamFixtures.randomSuffix();
        var admin = IamFixtures.operator(suffix)
                .id("admin-" + suffix)
                .email("admin-" + suffix + "@orochi.example")
                .role(OperatorRole.OPERATOR_ADMIN)
                .save(users, passwords);
        adminId = admin.id();
        adminToken = JwtTestSupport.token(issuer, admin);

        var support = IamFixtures.operator(suffix)
                .id("supp-" + suffix)
                .email("supp-" + suffix + "@orochi.example")
                .role(OperatorRole.OPERATOR_SUPPORT)
                .save(users, passwords);
        supportId = support.id();
        supportToken = JwtTestSupport.token(issuer, support);

        // Two tenants — SUPPORT is assigned to "assigned" only.
        assignedTenantId = "asn" + suffix;
        otherTenantId = "oth" + suffix;
        tenants.save(Tenant.create(assignedTenantId, "Acme Assigned " + suffix, adminId));
        tenants.save(Tenant.create(otherTenantId, "Other Co " + suffix, adminId));
        assignments.save(OperatorAssignment.grant(supportId, assignedTenantId, adminId));

        // One tenant user in each tenant — both share the searchable string
        // "findme" in their email so the SUPPORT-scoping check is meaningful.
        var assignedUser = IamFixtures.tenantUser(suffix, assignedTenantId)
                .id("tu-assigned-" + suffix)
                .email("findme-assigned-" + suffix + "@tenant.example")
                .firstName("Alice")
                .lastName("Findme")
                .save(users, passwords);
        assignedUserId = assignedUser.id();

        var otherUser = IamFixtures.tenantUser(suffix, otherTenantId)
                .id("tu-other-" + suffix)
                .email("findme-other-" + suffix + "@tenant.example")
                .firstName("Bob")
                .lastName("Findme")
                .save(users, passwords);
        otherUserId = otherUser.id();
    }

    @AfterEach
    void cleanup() {
        assignments.findAllByOperatorUserId(supportId).forEach(a -> assignments.deleteById(a.id()));
        users.deleteById(adminId);
        users.deleteById(supportId);
        users.deleteById(assignedUserId);
        users.deleteById(otherUserId);
        tenants.deleteById(assignedTenantId);
        tenants.deleteById(otherTenantId);
    }

    // ─────────────────────────────────────────────────────────────────────
    // Admin — unrestricted
    // ─────────────────────────────────────────────────────────────────────

    @Test
    @SuppressWarnings("unchecked")
    void admin_search_by_email_substring_finds_both_tenants() {
        var resp = IT.exchange(port, "/admin/api/users/search?q=findme-",
                HttpMethod.GET, adminToken, null, List.class);

        assertThat(resp.getStatusCode()).isEqualTo(HttpStatus.OK);
        var rows = (List<Map<String, Object>>) resp.getBody();
        assertThat(rows).extracting(r -> r.get("userId"))
                .contains(assignedUserId, otherUserId);
    }

    @Test
    @SuppressWarnings("unchecked")
    void admin_search_by_first_name_is_case_insensitive() {
        var resp = IT.exchange(port, "/admin/api/users/search?q=alice",
                HttpMethod.GET, adminToken, null, List.class);

        assertThat(resp.getStatusCode()).isEqualTo(HttpStatus.OK);
        var rows = (List<Map<String, Object>>) resp.getBody();
        assertThat(rows).extracting(r -> r.get("userId")).contains(assignedUserId);
    }

    @Test
    @SuppressWarnings("unchecked")
    void admin_search_can_surface_operators() {
        // Admin's own email contains "admin-<suffix>"; should appear.
        var resp = IT.exchange(port, "/admin/api/users/search?q=admin-" + suffix,
                HttpMethod.GET, adminToken, null, List.class);

        assertThat(resp.getStatusCode()).isEqualTo(HttpStatus.OK);
        var rows = (List<Map<String, Object>>) resp.getBody();
        assertThat(rows).extracting(r -> r.get("userId")).contains(adminId);
        assertThat(rows).anySatisfy(r -> {
            if (r.get("userId").equals(adminId)) {
                assertThat(r).containsEntry("kind", "OPERATOR");
                assertThat(r.get("tenantId")).isNull();
            }
        });
    }

    // ─────────────────────────────────────────────────────────────────────
    // SUPPORT — scoped to assigned tenants, tenant users only
    // ─────────────────────────────────────────────────────────────────────

    @Test
    @SuppressWarnings("unchecked")
    void support_sees_only_users_in_their_assigned_tenants() {
        var resp = IT.exchange(port, "/admin/api/users/search?q=findme-",
                HttpMethod.GET, supportToken, null, List.class);

        assertThat(resp.getStatusCode()).isEqualTo(HttpStatus.OK);
        var rows = (List<Map<String, Object>>) resp.getBody();
        assertThat(rows).extracting(r -> r.get("userId"))
                .contains(assignedUserId)
                .doesNotContain(otherUserId);
    }

    @Test
    @SuppressWarnings("unchecked")
    void support_never_sees_operators() {
        // The "admin-<suffix>" operator would be a match by email if
        // operators were in scope; SUPPORT should get an empty result.
        var resp = IT.exchange(port, "/admin/api/users/search?q=admin-" + suffix,
                HttpMethod.GET, supportToken, null, List.class);

        assertThat(resp.getStatusCode()).isEqualTo(HttpStatus.OK);
        var rows = (List<Map<String, Object>>) resp.getBody();
        assertThat(rows).extracting(r -> r.get("userId"))
                .doesNotContain(adminId, supportId);
    }

    // ─────────────────────────────────────────────────────────────────────
    // Validation
    // ─────────────────────────────────────────────────────────────────────

    @Test
    @SuppressWarnings("unchecked")
    void short_query_returns_empty_list() {
        // A single-character query would slurp ~the entire user table — the
        // service short-circuits below MIN_QUERY_LENGTH.
        var resp = IT.exchange(port, "/admin/api/users/search?q=a",
                HttpMethod.GET, adminToken, null, List.class);

        assertThat(resp.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat((List<?>) resp.getBody()).isEmpty();
    }

    @Test
    void requires_authentication() {
        var resp = IT.getAnon(port, "/admin/api/users/search?q=findme-", Map.class);
        assertThat(resp.getStatusCode()).isEqualTo(HttpStatus.UNAUTHORIZED);
    }
}
