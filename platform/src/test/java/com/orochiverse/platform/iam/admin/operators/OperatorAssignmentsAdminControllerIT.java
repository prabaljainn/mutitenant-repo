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
import com.orochiverse.platform.common.tenant.TenantId;
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
class OperatorAssignmentsAdminControllerIT {

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
    @Autowired MongoClient mongo;

    private String suffix;
    private String adminId;
    private String supportId;
    private String adminToken;
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
                .id("supp-" + suffix)
                .email("supp-" + suffix + "@orochi.example")
                .role(OperatorRole.OPERATOR_SUPPORT)
                .save(users, passwords);
        adminId = admin.id();
        supportId = support.id();
        adminToken = JwtTestSupport.token(issuer, admin);

        tenantId = "p17b" + suffix;
        tenants.save(Tenant.create(tenantId, "Acme " + suffix, adminId));
    }

    @AfterEach
    void cleanup() {
        assignments.findAllByOperatorUserId(supportId).forEach(a -> assignments.deleteById(a.id()));
        users.deleteById(adminId);
        users.deleteById(supportId);
        tenants.deleteById(tenantId);
        mongo.getDatabase(TenantId.dbName(tenantId)).drop();
    }

    @Test
    @SuppressWarnings("unchecked")
    void grant_assignment_succeeds_and_appears_in_list() {
        var grant = IT.exchange(port, "/admin/api/operators/" + supportId + "/assignments",
                HttpMethod.POST, adminToken,
                Map.of("tenantId", tenantId), Map.class);
        assertThat(grant.getStatusCode()).isEqualTo(HttpStatus.CREATED);
        assertThat(grant.getBody()).containsEntry("operatorUserId", supportId);
        assertThat(grant.getBody()).containsEntry("tenantId", tenantId);

        var list = IT.exchange(port, "/admin/api/operators/" + supportId + "/assignments",
                HttpMethod.GET, adminToken, null, List.class);
        assertThat(list.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(list.getBody()).hasSize(1);
    }

    @Test
    @SuppressWarnings("unchecked")
    void grant_for_unknown_tenant_returns_404() {
        var resp = IT.exchange(port, "/admin/api/operators/" + supportId + "/assignments",
                HttpMethod.POST, adminToken,
                Map.of("tenantId", "no-such-tenant"), Map.class);

        assertThat(resp.getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
    }

    @Test
    @SuppressWarnings("unchecked")
    void grant_for_unknown_operator_returns_404() {
        var resp = IT.exchange(port, "/admin/api/operators/operator-deadbeef/assignments",
                HttpMethod.POST, adminToken,
                Map.of("tenantId", tenantId), Map.class);

        assertThat(resp.getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
    }

    @Test
    @SuppressWarnings("unchecked")
    void duplicate_grant_returns_409() {
        IT.exchange(port, "/admin/api/operators/" + supportId + "/assignments",
                HttpMethod.POST, adminToken, Map.of("tenantId", tenantId), Map.class);

        var dup = IT.exchange(port, "/admin/api/operators/" + supportId + "/assignments",
                HttpMethod.POST, adminToken, Map.of("tenantId", tenantId), Map.class);

        assertThat(dup.getStatusCode()).isEqualTo(HttpStatus.CONFLICT);
    }

    @Test
    @SuppressWarnings("unchecked")
    void revoke_assignment_succeeds() {
        IT.exchange(port, "/admin/api/operators/" + supportId + "/assignments",
                HttpMethod.POST, adminToken, Map.of("tenantId", tenantId), Map.class);

        var del = IT.exchange(port,
                "/admin/api/operators/" + supportId + "/assignments/" + tenantId,
                HttpMethod.DELETE, adminToken, null, Void.class);
        assertThat(del.getStatusCode()).isEqualTo(HttpStatus.NO_CONTENT);

        assertThat(assignments.existsByOperatorUserIdAndTenantId(supportId, tenantId)).isFalse();
    }

    @Test
    @SuppressWarnings("unchecked")
    void revoke_nonexistent_assignment_returns_404() {
        var del = IT.exchange(port,
                "/admin/api/operators/" + supportId + "/assignments/" + tenantId,
                HttpMethod.DELETE, adminToken, null, Map.class);

        assertThat(del.getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
    }

}
