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
 * Tenant-side read view of the settings store. The interesting checks
 * here are the RBAC slices: OWNER and ADMIN can read; EDITOR and
 * VIEWER cannot; and there is no path that lets one tenant ask about
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
    private String ownerAId;
    private String adminAId;
    private String editorAId;
    private String viewerAId;
    private String ownerBId;
    private String ownerAToken;
    private String adminAToken;
    private String editorAToken;
    private String viewerAToken;
    private String ownerBToken;

    @BeforeEach
    void setUp() {
        suffix = IamFixtures.randomSuffix();
        tenantA = "ta" + suffix;
        tenantB = "tb" + suffix;
        tenants.save(Tenant.newTrial(tenantA, "A " + suffix, "STARTER", "system"));
        tenants.save(Tenant.newTrial(tenantB, "B " + suffix, "STARTER", "system"));

        // Four tenant-A users, one per role — covers the entire RBAC matrix.
        var ownerA = IamFixtures.tenantUser("owner-a-" + suffix, tenantA)
                .email("owner-a-" + suffix + "@a.example")
                .role(TenantRole.TENANT_OWNER).save(users, passwords);
        var adminA = IamFixtures.tenantUser("admin-a-" + suffix, tenantA)
                .email("admin-a-" + suffix + "@a.example")
                .role(TenantRole.ADMIN).save(users, passwords);
        var editorA = IamFixtures.tenantUser("editor-a-" + suffix, tenantA)
                .email("editor-a-" + suffix + "@a.example")
                .role(TenantRole.EDITOR).save(users, passwords);
        var viewerA = IamFixtures.tenantUser("viewer-a-" + suffix, tenantA)
                .email("viewer-a-" + suffix + "@a.example")
                .role(TenantRole.VIEWER).save(users, passwords);
        var ownerB = IamFixtures.tenantUser("owner-b-" + suffix, tenantB)
                .email("owner-b-" + suffix + "@b.example")
                .role(TenantRole.TENANT_OWNER).save(users, passwords);

        ownerAId = ownerA.id();
        adminAId = adminA.id();
        editorAId = editorA.id();
        viewerAId = viewerA.id();
        ownerBId = ownerB.id();

        ownerAToken = JwtTestSupport.token(issuer, ownerA);
        adminAToken = JwtTestSupport.token(issuer, adminA);
        editorAToken = JwtTestSupport.token(issuer, editorA);
        viewerAToken = JwtTestSupport.token(issuer, viewerA);
        ownerBToken = JwtTestSupport.token(issuer, ownerB);

        // Seed MQTT for tenant A only — so we can show that B's OWNER
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
        users.deleteById(ownerAId);
        users.deleteById(adminAId);
        users.deleteById(editorAId);
        users.deleteById(viewerAId);
        users.deleteById(ownerBId);
        settings.deleteAllByTenantId(tenantA);
        settings.deleteAllByTenantId(tenantB);
        tenants.deleteById(tenantA);
        tenants.deleteById(tenantB);
    }

    // ─────────────────────────────────────────────────────────────────────
    // OWNER / ADMIN: allowed to read
    // ─────────────────────────────────────────────────────────────────────

    @Test
    @SuppressWarnings("unchecked")
    void owner_can_read_own_tenants_mqtt_and_password_is_masked() {
        var resp = IT.exchange(port, "/api/tenant/settings/MQTT",
                HttpMethod.GET, ownerAToken, null, Map.class);

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
    void admin_can_read_own_tenants_settings() {
        var resp = IT.exchange(port, "/api/tenant/settings/MQTT",
                HttpMethod.GET, adminAToken, null, Map.class);
        assertThat(resp.getStatusCode()).isEqualTo(HttpStatus.OK);
    }

    @Test
    @SuppressWarnings("unchecked")
    void list_returns_only_configured_kinds_for_the_callers_tenant() {
        var resp = IT.exchange(port, "/api/tenant/settings",
                HttpMethod.GET, ownerAToken, null, Map.class);
        assertThat(resp.getStatusCode()).isEqualTo(HttpStatus.OK);

        var items = (List<Map<String, Object>>) resp.getBody().get("items");
        assertThat(items).hasSize(1);
        assertThat(items.get(0)).containsEntry("kind", "MQTT");
        assertThat(items.get(0)).containsEntry("tenantId", tenantA);
    }

    @Test
    @SuppressWarnings("unchecked")
    void unconfigured_kind_returns_blank_record_not_404() {
        // DJI was never set for tenant A.
        var resp = IT.exchange(port, "/api/tenant/settings/DJI",
                HttpMethod.GET, ownerAToken, null, Map.class);
        assertThat(resp.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(resp.getBody()).containsEntry("configured", false);
        assertThat((Map<String, Object>) resp.getBody().get("values")).isEmpty();
    }

    // ─────────────────────────────────────────────────────────────────────
    // EDITOR / VIEWER: forbidden
    // ─────────────────────────────────────────────────────────────────────

    @Test
    @SuppressWarnings("unchecked")
    void editor_cannot_read_settings() {
        var resp = IT.exchange(port, "/api/tenant/settings/MQTT",
                HttpMethod.GET, editorAToken, null, Map.class);
        assertThat(resp.getStatusCode()).isEqualTo(HttpStatus.FORBIDDEN);
    }

    @Test
    @SuppressWarnings("unchecked")
    void viewer_cannot_read_settings() {
        var resp = IT.exchange(port, "/api/tenant/settings/MQTT",
                HttpMethod.GET, viewerAToken, null, Map.class);
        assertThat(resp.getStatusCode()).isEqualTo(HttpStatus.FORBIDDEN);
    }

    // ─────────────────────────────────────────────────────────────────────
    // Cross-tenant: tenant B owner sees B's settings, never A's
    // ─────────────────────────────────────────────────────────────────────

    @Test
    @SuppressWarnings("unchecked")
    void another_tenants_owner_does_not_see_my_settings() {
        // Tenant B has nothing configured — read returns blank for that
        // tenant, NOT A's data. The id comes from B's JWT tid, not from
        // any URL the caller can manipulate.
        var resp = IT.exchange(port, "/api/tenant/settings/MQTT",
                HttpMethod.GET, ownerBToken, null, Map.class);
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
        // Spring returns 401 when no PUT handler matches an authenticated
        // path under .anyRequest().authenticated() — the entry point fires
        // instead of MVC's 405. Either way the call is rejected; the test
        // pins down the rejection, not the precise status code, plus the
        // proof-by-state that nothing changed in storage.
        assertThat(resp.getStatusCode().is4xxClientError()).isTrue();
        var stored = settings.findByTenantIdAndKind(tenantA, SettingsKind.MQTT).orElseThrow();
        assertThat(stored.values()).containsEntry("host", "mqtt.a.example");
    }
}
