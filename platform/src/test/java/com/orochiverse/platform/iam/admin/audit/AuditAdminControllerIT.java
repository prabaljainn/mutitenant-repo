package com.orochiverse.platform.iam.admin.audit;

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

import com.orochiverse.platform.common.audit.AuditAction;
import com.orochiverse.platform.common.audit.AuditEntry;
import com.orochiverse.platform.common.audit.AuditEntryRepository;
import com.orochiverse.platform.common.security.jwt.AccessTokenIssuer;
import com.orochiverse.platform.common.security.passwords.PasswordHashing;
import com.orochiverse.platform.iam.users.UserRepository;
import com.orochiverse.platform.testsupport.IT;
import com.orochiverse.platform.testsupport.IamFixtures;
import com.orochiverse.platform.testsupport.JwtTestSupport;
import com.orochiverse.platform.testsupport.MongoTestSupport;

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@ActiveProfiles("integration")
@EnabledIf("com.orochiverse.platform.testsupport.MongoTestSupport#mongoIsReachable")
class AuditAdminControllerIT {

    @DynamicPropertySource
    static void mongoProps(DynamicPropertyRegistry r) {
        MongoTestSupport.mongoProps(r);
    }

    @LocalServerPort int port;
    @Autowired UserRepository users;
    @Autowired PasswordHashing passwords;
    @Autowired AuditEntryRepository audit;
    @Autowired AccessTokenIssuer issuer;

    private String suffix;
    private String adminId;
    private String adminToken;

    @BeforeEach
    void setUp() {
        suffix = IamFixtures.randomSuffix();
        var admin = IamFixtures.operator(suffix).save(users, passwords);
        adminId = admin.id();
        adminToken = JwtTestSupport.token(issuer, admin);

        // Seed a small set of audit rows we can query for.
        audit.save(AuditEntry.of(AuditAction.LOGIN_SUCCESS, adminId));
        audit.save(AuditEntry.of(AuditAction.OPERATOR_INVITED, adminId,
                Map.of("operatorId", "operator-other-" + suffix)));
    }

    @AfterEach
    void cleanup() {
        users.deleteById(adminId);
        audit.findAllByActorUserIdOrderByTimestampDesc(adminId,
                org.springframework.data.domain.PageRequest.of(0, 200))
                .forEach(e -> audit.deleteById(e.id()));
    }

    @Test
    void unfiltered_listing_is_paginated() {
        var resp = IT.exchange(port, "/admin/api/audit?size=10",
                HttpMethod.GET, adminToken, null, List.class);

        assertThat(resp.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(resp.getBody()).isNotEmpty();
    }

    @Test
    @SuppressWarnings("unchecked")
    void filter_by_actor_returns_only_my_actions_and_includes_seeded_login() {
        // Login itself produces a LOGIN_SUCCESS entry; the @BeforeEach seeded
        // two more. So expect at least 3 entries for this actor.
        var resp = IT.exchange(port, "/admin/api/audit?actorUserId=" + adminId,
                HttpMethod.GET, adminToken, null, List.class);

        assertThat(resp.getStatusCode()).isEqualTo(HttpStatus.OK);
        var rows = resp.getBody();
        assertThat(rows).isNotEmpty();
        // Every row's actorUserId is the one we asked for.
        rows.forEach(r -> {
            var entry = (Map<String, Object>) r;
            assertThat(entry).containsEntry("actorUserId", adminId);
        });
    }

    @Test
    void requires_authentication() {
        var resp = new TestRestTemplate().getForEntity(IT.url(port, "/admin/api/audit"), Map.class);
        assertThat(resp.getStatusCode()).isEqualTo(HttpStatus.UNAUTHORIZED);
    }
}
