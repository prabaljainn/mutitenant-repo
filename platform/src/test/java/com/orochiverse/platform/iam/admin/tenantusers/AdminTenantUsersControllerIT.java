package com.orochiverse.platform.iam.admin.tenantusers;

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

import com.mongodb.client.MongoClient;

import com.orochiverse.platform.common.security.jwt.AccessTokenIssuer;
import com.orochiverse.platform.common.security.passwords.PasswordHashing;
import com.orochiverse.platform.common.security.principals.OperatorRole;
import com.orochiverse.platform.common.security.principals.TenantRole;
import com.orochiverse.platform.common.tenant.TenantId;
import com.orochiverse.platform.iam.tenants.Tenant;
import com.orochiverse.platform.iam.tenants.TenantRepository;
import com.orochiverse.platform.iam.users.UserRepository;
import com.orochiverse.platform.testsupport.IT;
import com.orochiverse.platform.testsupport.IamFixtures;
import com.orochiverse.platform.testsupport.JwtTestSupport;
import com.orochiverse.platform.testsupport.MongoTestSupport;

/**
 * Exercises the admin-side tenant-user CRUD surface. The interesting
 * proof here is that the operator never holds a tenant-bound token —
 * the controller binds {@code TenantContext} from the URL path on
 * each call, so the underlying {@link com.orochiverse.platform.iam.tenantadmin.TenantUsersService}
 * works exactly as it does for tenant-side requests.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@ActiveProfiles("integration")
@EnabledIf("com.orochiverse.platform.testsupport.MongoTestSupport#mongoIsReachable")
class AdminTenantUsersControllerIT {

    @DynamicPropertySource
    static void mongoProps(DynamicPropertyRegistry r) { MongoTestSupport.mongoProps(r); }

    @LocalServerPort int port;
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
    private String tenantUserId;
    private String createdInviteId;

    @BeforeEach
    void setUp() {
        suffix = IamFixtures.randomSuffix();
        var admin = IamFixtures.operator(suffix)
                .id("admin-" + suffix)
                .email("admin-" + suffix + "@orochi.example")
                .role(OperatorRole.OPERATOR_ADMIN)
                .save(users, passwords);
        var support = IamFixtures.operator(suffix)
                .id("supp-" + suffix)
                .email("supp-" + suffix + "@orochi.example")
                .role(OperatorRole.OPERATOR_SUPPORT)
                .save(users, passwords);
        adminId = admin.id();
        supportId = support.id();
        adminToken = JwtTestSupport.token(issuer, admin);
        supportToken = JwtTestSupport.token(issuer, support);

        tenantId = "atu" + suffix;
        tenants.save(Tenant.newTrial(tenantId, "Atu " + suffix, "STARTER", adminId));

        // One existing TENANT_OWNER so single-owner rules can be tested.
        tenantUserId = IamFixtures.tenantUser("own-" + suffix, tenantId)
                .role(TenantRole.TENANT_OWNER)
                .save(users, passwords).id();
    }

    @AfterEach
    void cleanup() {
        users.deleteById(adminId);
        users.deleteById(supportId);
        users.deleteById(tenantUserId);
        if (createdInviteId != null) users.deleteById(createdInviteId);
        tenants.deleteById(tenantId);
        mongo.getDatabase(TenantId.dbName(tenantId)).drop();
    }

    @Test
    @SuppressWarnings("unchecked")
    void list_returns_only_the_tenants_users_for_any_operator() {
        var resp = IT.exchange(port, "/admin/api/tenants/" + tenantId + "/users",
                HttpMethod.GET, supportToken, null, List.class);
        assertThat(resp.getStatusCode()).isEqualTo(HttpStatus.OK);
        var ids = resp.getBody().stream()
                .map(r -> ((Map<String, Object>) r).get("id")).toList();
        assertThat(ids).contains(tenantUserId);
    }

    @Test
    @SuppressWarnings("unchecked")
    void admin_invite_creates_tenant_user_without_switch_tenant() {
        var email = "new-" + suffix + "@a.example";
        var resp = IT.exchange(port, "/admin/api/tenants/" + tenantId + "/users",
                HttpMethod.POST, adminToken,
                Map.of("email", email, "firstName", "N", "lastName", "U", "role", "EDITOR"),
                Map.class);

        assertThat(resp.getStatusCode()).isEqualTo(HttpStatus.CREATED);
        createdInviteId = (String) resp.getBody().get("id");

        var stored = users.findById(createdInviteId).orElseThrow();
        assertThat(stored.tenantId()).isEqualTo(tenantId);
        assertThat(stored.passwordHash()).isNull(); // INVITED, no password yet
    }

    @Test
    @SuppressWarnings("unchecked")
    void support_role_cannot_invite() {
        var resp = IT.exchange(port, "/admin/api/tenants/" + tenantId + "/users",
                HttpMethod.POST, supportToken,
                Map.of("email", "x-" + suffix + "@a.example",
                        "firstName", "X", "lastName", "Y", "role", "EDITOR"),
                Map.class);
        assertThat(resp.getStatusCode()).isEqualTo(HttpStatus.FORBIDDEN);
    }

    @Test
    @SuppressWarnings("unchecked")
    void unknown_tenant_returns_404() {
        var resp = IT.exchange(port, "/admin/api/tenants/no-such-tenant/users",
                HttpMethod.GET, adminToken, null, Map.class);
        assertThat(resp.getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
    }

    @Test
    @SuppressWarnings("unchecked")
    void owner_protection_is_inherited_from_the_tenant_side_service() {
        // The one TENANT_OWNER cannot be demoted — same rule the tenant-
        // side service enforces.
        var resp = IT.exchange(port, "/admin/api/tenants/" + tenantId + "/users/" + tenantUserId,
                HttpMethod.PUT, adminToken,
                Map.of("role", "ADMIN"), Map.class);
        assertThat(resp.getStatusCode()).isEqualTo(HttpStatus.UNPROCESSABLE_ENTITY);
    }
}
