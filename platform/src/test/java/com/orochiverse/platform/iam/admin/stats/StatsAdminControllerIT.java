package com.orochiverse.platform.iam.admin.stats;

import static org.assertj.core.api.Assertions.assertThat;

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
import com.orochiverse.platform.common.security.principals.TenantRole;
import com.orochiverse.platform.iam.tenants.Tenant;
import com.orochiverse.platform.iam.tenants.TenantRepository;
import com.orochiverse.platform.iam.users.UserRepository;
import com.orochiverse.platform.iam.users.UserStatus;
import com.orochiverse.platform.testsupport.IT;
import com.orochiverse.platform.testsupport.IamFixtures;
import com.orochiverse.platform.testsupport.JwtTestSupport;
import com.orochiverse.platform.testsupport.MongoTestSupport;

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@ActiveProfiles("integration")
@EnabledIf("com.orochiverse.platform.testsupport.MongoTestSupport#mongoIsReachable")
class StatsAdminControllerIT {

    @DynamicPropertySource
    static void mongoProps(DynamicPropertyRegistry r) { MongoTestSupport.mongoProps(r); }

    @LocalServerPort int port;
    @Autowired UserRepository users;
    @Autowired TenantRepository tenants;
    @Autowired PasswordHashing passwords;
    @Autowired AccessTokenIssuer issuer;

    private String suffix;
    private String adminId;
    private String adminToken;
    private String tenantId;
    private String activeUserId;
    private String invitedUserId;

    @BeforeEach
    void setUp() {
        suffix = IamFixtures.randomSuffix();
        var admin = IamFixtures.operator(suffix).save(users, passwords);
        adminId = admin.id();
        adminToken = JwtTestSupport.token(issuer, admin);

        tenantId = "stats" + suffix;
        tenants.save(Tenant.create(tenantId, "Stats " + suffix, adminId));

        activeUserId = IamFixtures.tenantUser("active-" + suffix, tenantId)
                .role(TenantRole.ADMIN)
                .save(users, passwords).id();
        invitedUserId = IamFixtures.tenantUser("invited-" + suffix, tenantId)
                .role(TenantRole.MEMBER)
                .status(UserStatus.INVITED)
                .noPassword()
                .save(users, passwords).id();
    }

    @AfterEach
    void cleanup() {
        users.deleteById(adminId);
        users.deleteById(activeUserId);
        users.deleteById(invitedUserId);
        tenants.deleteById(tenantId);
    }

    @Test
    @SuppressWarnings("unchecked")
    void overview_returns_aggregate_counters() {
        var resp = IT.exchange(port, "/admin/api/stats/overview",
                HttpMethod.GET, adminToken, null, Map.class);
        assertThat(resp.getStatusCode()).isEqualTo(HttpStatus.OK);
        Map<String, Object> body = resp.getBody();
        assertThat(body).containsKeys("tenants", "tenantUsers", "pendingInvites");

        long tenantsCount = ((Number) body.get("tenants")).longValue();
        long tenantUsers  = ((Number) body.get("tenantUsers")).longValue();
        long pending      = ((Number) body.get("pendingInvites")).longValue();

        assertThat(tenantsCount).isGreaterThanOrEqualTo(1);
        assertThat(tenantUsers).isGreaterThanOrEqualTo(2);
        assertThat(pending).isGreaterThanOrEqualTo(1);
    }

    @Test
    void overview_requires_operator_role() {
        var resp = IT.exchange(port, "/admin/api/stats/overview",
                HttpMethod.GET, null, null, Map.class);
        assertThat(resp.getStatusCode()).isEqualTo(HttpStatus.UNAUTHORIZED);
    }
}
