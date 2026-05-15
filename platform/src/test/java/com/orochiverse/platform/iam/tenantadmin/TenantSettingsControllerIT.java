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
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

import com.orochiverse.platform.common.security.jwt.AccessTokenIssuer;
import com.orochiverse.platform.common.security.passwords.PasswordHashing;
import com.orochiverse.platform.common.security.principals.TenantRole;
import com.orochiverse.platform.iam.settings.SettingsKind;
import com.orochiverse.platform.iam.settings.TenantSetting;
import com.orochiverse.platform.iam.settings.TenantSettingsRepository;
import com.orochiverse.platform.iam.tenants.Tenant;
import com.orochiverse.platform.iam.tenants.TenantRepository;
import com.orochiverse.platform.iam.users.UserRepository;
import com.orochiverse.platform.testsupport.IT;
import com.orochiverse.platform.testsupport.IamFixtures;
import com.orochiverse.platform.testsupport.JwtTestSupport;
import com.orochiverse.platform.testsupport.MongoTestSupport;

/**
 * Tenant-side read view of the settings store. RBAC: ADMIN can read,
 * MEMBER cannot. There is no path that lets one tenant ask about
 * another tenant's settings (the tenant id comes from
 * {@code TenantContext}, not the URL).
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@ActiveProfiles("integration")
@EnabledIf("com.orochiverse.platform.testsupport.MongoTestSupport#mongoIsReachable")
class TenantSettingsControllerIT {

    @DynamicPropertySource
    static void mongoProps(DynamicPropertyRegistry r) { MongoTestSupport.mongoProps(r); }

    @LocalServerPort int port;
    @Autowired UserRepository users;
    @Autowired TenantRepository tenants;
    @Autowired TenantSettingsRepository settings;
    @Autowired PasswordHashing passwords;
    @Autowired AccessTokenIssuer issuer;

    private String suffix;
    private String tenantA;
    private String tenantB;
    private String adminAId;
    private String memberAId;
    private String adminBId;
    private String adminAToken;
    private String memberAToken;
    private String adminBToken;

    @BeforeEach
    void setUp() {
        suffix = IamFixtures.randomSuffix();
        tenantA = "ta" + suffix;
        tenantB = "tb" + suffix;
        tenants.save(Tenant.create(tenantA, "A " + suffix, "system"));
        tenants.save(Tenant.create(tenantB, "B " + suffix, "system"));

        var adminA = IamFixtures.tenantUser("admin-a-" + suffix, tenantA)
                .email("admin-a-" + suffix + "@a.example")
                .role(TenantRole.ADMIN).save(users, passwords);
        var memberA = IamFixtures.tenantUser("member-a-" + suffix, tenantA)
                .email("member-a-" + suffix + "@a.example")
                .role(TenantRole.MEMBER).save(users, passwords);
        var adminB = IamFixtures.tenantUser("admin-b-" + suffix, tenantB)
                .email("admin-b-" + suffix + "@b.example")
                .role(TenantRole.ADMIN).save(users, passwords);

        adminAId = adminA.id();
        memberAId = memberA.id();
        adminBId = adminB.id();

        adminAToken = JwtTestSupport.token(issuer, adminA);
        memberAToken = JwtTestSupport.token(issuer, memberA);
        adminBToken = JwtTestSupport.token(issuer, adminB);

        // Seed MQTT for tenant A only — so we can show that B's ADMIN
        // doesn't accidentally see A's config.
        settings.save(TenantSetting.fresh(tenantA, SettingsKind.MQTT, Map.of(
                "host", "mqtt.a.example",
                "port", 8883,
                "transport", "tls",
                "username", "u",
                "password", "secret-A")));
    }

    @AfterEach
    void cleanup() {
        users.deleteById(adminAId);
        users.deleteById(memberAId);
        users.deleteById(adminBId);
        settings.deleteAllByTenantId(tenantA);
        settings.deleteAllByTenantId(tenantB);
        tenants.deleteById(tenantA);
        tenants.deleteById(tenantB);
    }

    // ─────────────────────────────────────────────────────────────────────
    // ADMIN: allowed to read
    // ─────────────────────────────────────────────────────────────────────

    @Test
    @SuppressWarnings("unchecked")
    void admin_can_read_own_tenants_mqtt_and_password_is_masked() {
        var resp = IT.exchange(port, "/api/tenant/settings/MQTT",
                HttpMethod.GET, adminAToken, null, Map.class);

        assertThat(resp.getStatusCode()).isEqualTo(HttpStatus.OK);
        Map<String, Object> body = resp.getBody();
        assertThat(body).containsEntry("configured", true);

        Map<String, Object> values = (Map<String, Object>) body.get("values");
        assertThat(values).doesNotContainKey("password");
        assertThat(values).containsEntry("host", "mqtt.a.example");

        List<String> secrets = (List<String>) body.get("secrets");
        assertThat(secrets).containsExactly("password");
    }

    @Test
    @SuppressWarnings("unchecked")
    void list_returns_only_configured_kinds_for_the_callers_tenant() {
        var resp = IT.exchange(port, "/api/tenant/settings",
                HttpMethod.GET, adminAToken, null, Map.class);
        assertThat(resp.getStatusCode()).isEqualTo(HttpStatus.OK);

        var items = (List<Map<String, Object>>) resp.getBody().get("items");
        assertThat(items).hasSize(1);
        assertThat(items.get(0)).containsEntry("kind", "MQTT");
        assertThat(items.get(0)).containsEntry("tenantId", tenantA);
    }

    @Test
    @SuppressWarnings("unchecked")
    void unconfigured_kind_returns_blank_record_not_404() {
        var resp = IT.exchange(port, "/api/tenant/settings/DJI",
                HttpMethod.GET, adminAToken, null, Map.class);
        assertThat(resp.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(resp.getBody()).containsEntry("configured", false);
        assertThat((Map<String, Object>) resp.getBody().get("values")).isEmpty();
    }

    // ─────────────────────────────────────────────────────────────────────
    // MEMBER: forbidden
    // ─────────────────────────────────────────────────────────────────────

    @Test
    @SuppressWarnings("unchecked")
    void member_cannot_read_settings() {
        var resp = IT.exchange(port, "/api/tenant/settings/MQTT",
                HttpMethod.GET, memberAToken, null, Map.class);
        assertThat(resp.getStatusCode()).isEqualTo(HttpStatus.FORBIDDEN);
    }

    // ─────────────────────────────────────────────────────────────────────
    // Cross-tenant: tenant B admin sees B's settings, never A's
    // ─────────────────────────────────────────────────────────────────────

    @Test
    @SuppressWarnings("unchecked")
    void another_tenants_admin_does_not_see_my_settings() {
        var resp = IT.exchange(port, "/api/tenant/settings/MQTT",
                HttpMethod.GET, adminBToken, null, Map.class);
        assertThat(resp.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(resp.getBody()).containsEntry("configured", false);
        assertThat(resp.getBody()).containsEntry("tenantId", tenantB);
    }

    // ─────────────────────────────────────────────────────────────────────
    // No write path on this surface
    // ─────────────────────────────────────────────────────────────────────

    @Test
    @SuppressWarnings("unchecked")
    void admin_cannot_PUT_through_tenant_side() {
        var resp = IT.exchange(port, "/api/tenant/settings/MQTT",
                HttpMethod.PUT, adminAToken,
                Map.of("values", Map.of("host", "intruder.example", "port", 1883, "transport", "tls")),
                Map.class);
        assertThat(resp.getStatusCode().is4xxClientError()).isTrue();
        var stored = settings.findByTenantIdAndKind(tenantA, SettingsKind.MQTT).orElseThrow();
        assertThat(stored.values()).containsEntry("host", "mqtt.a.example");
    }
}
