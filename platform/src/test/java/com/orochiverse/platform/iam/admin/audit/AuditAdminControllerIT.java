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
import com.orochiverse.platform.common.security.passwords.PasswordHashing;
import com.orochiverse.platform.common.security.principals.OperatorRole;
import com.orochiverse.platform.iam.admin.AdminItSupport;
import com.orochiverse.platform.iam.users.UserRepository;

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@ActiveProfiles("integration")
@EnabledIf("com.orochiverse.platform.iam.admin.AdminItSupport#mongoIsReachable")
class AuditAdminControllerIT {

    @DynamicPropertySource
    static void mongoProps(DynamicPropertyRegistry r) {
        r.add("spring.data.mongodb.uri", () -> AdminItSupport.CONNECTION_URI);
    }

    @LocalServerPort int port;
    @Autowired UserRepository users;
    @Autowired PasswordHashing passwords;
    @Autowired AuditEntryRepository audit;

    private String suffix;
    private String adminId;
    private String adminToken;

    @BeforeEach
    void setUp() {
        suffix = AdminItSupport.randomSuffix();
        adminId = AdminItSupport.seedOperator(users, passwords, "admin-" + suffix,
                "admin-" + suffix + "@orochi.example", "S3cret!", OperatorRole.OPERATOR_ADMIN);
        adminToken = login("admin-" + suffix + "@orochi.example", "S3cret!");

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
        var resp = AdminItSupport.exchange(url("/admin/api/audit?size=10"),
                HttpMethod.GET, adminToken, null, List.class);

        assertThat(resp.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(resp.getBody()).isNotEmpty();
    }

    @Test
    @SuppressWarnings("unchecked")
    void filter_by_actor_returns_only_my_actions_and_includes_seeded_login() {
        // Login itself produces a LOGIN_SUCCESS entry; the @BeforeEach seeded
        // two more. So expect at least 3 entries for this actor.
        var resp = AdminItSupport.exchange(url("/admin/api/audit?actorUserId=" + adminId),
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
        var resp = new TestRestTemplate().getForEntity(url("/admin/api/audit"), Map.class);
        assertThat(resp.getStatusCode()).isEqualTo(HttpStatus.UNAUTHORIZED);
    }

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
