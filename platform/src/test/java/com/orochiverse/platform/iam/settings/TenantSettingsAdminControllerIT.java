package com.orochiverse.platform.iam.settings;

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
import com.orochiverse.platform.iam.tenants.Tenant;
import com.orochiverse.platform.iam.tenants.TenantRepository;
import com.orochiverse.platform.iam.users.UserRepository;
import com.orochiverse.platform.testsupport.IT;
import com.orochiverse.platform.testsupport.IamFixtures;
import com.orochiverse.platform.testsupport.JwtTestSupport;
import com.orochiverse.platform.testsupport.MongoTestSupport;

/**
 * End-to-end exercise of the extensible per-tenant settings store.
 * Covers the cross-cutting behaviour that the unit tests can't reach:
 * controller wiring, persistence, secret-masking on the JSON boundary,
 * the /test endpoint's response shape, and cleanup on tenant archive.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@ActiveProfiles("integration")
@EnabledIf("com.orochiverse.platform.testsupport.MongoTestSupport#mongoIsReachable")
class TenantSettingsAdminControllerIT {

    @DynamicPropertySource
    static void mongoProps(DynamicPropertyRegistry r) { MongoTestSupport.mongoProps(r); }

    @LocalServerPort int port;
    @Autowired UserRepository users;
    @Autowired TenantRepository tenants;
    @Autowired TenantSettingsRepository settings;
    @Autowired PasswordHashing passwords;
    @Autowired AccessTokenIssuer issuer;

    private String suffix;
    private String adminId;
    private String adminToken;
    private String tenantId;

    @BeforeEach
    void setUp() {
        suffix = IamFixtures.randomSuffix();
        var admin = IamFixtures.operator(suffix).save(users, passwords);
        adminId = admin.id();
        adminToken = JwtTestSupport.token(issuer, admin);

        tenantId = "set" + suffix;
        tenants.save(Tenant.create(tenantId, "Set " + suffix, adminId));
    }

    @AfterEach
    void cleanup() {
        users.deleteById(adminId);
        settings.deleteAllByTenantId(tenantId);
        tenants.deleteById(tenantId);
    }

    @Test
    @SuppressWarnings("unchecked")
    void get_on_unconfigured_kind_returns_blank_not_404() {
        var resp = IT.exchange(port, "/admin/api/tenants/" + tenantId + "/settings/MQTT",
                HttpMethod.GET, adminToken, null, Map.class);
        assertThat(resp.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(resp.getBody()).containsEntry("configured", false);
        assertThat((Map<String, Object>) resp.getBody().get("values")).isEmpty();
    }

    @Test
    @SuppressWarnings("unchecked")
    void upsert_then_read_masks_password() {
        var put = IT.exchange(port, "/admin/api/tenants/" + tenantId + "/settings/MQTT",
                HttpMethod.PUT, adminToken,
                Map.of("values", Map.of(
                        "host", "mqtt.example.io",
                        "port", 8883,
                        "transport", "tls",
                        "username", "u",
                        "password", "s3cret")),
                Map.class);
        assertThat(put.getStatusCode()).isEqualTo(HttpStatus.OK);

        var get = IT.exchange(port, "/admin/api/tenants/" + tenantId + "/settings/MQTT",
                HttpMethod.GET, adminToken, null, Map.class);
        assertThat(get.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(get.getBody()).containsEntry("configured", true);
        var values = (Map<String, Object>) get.getBody().get("values");
        assertThat(values).doesNotContainKey("password");
        assertThat(values).containsEntry("host", "mqtt.example.io");
        var secrets = (List<String>) get.getBody().get("secrets");
        assertThat(secrets).containsExactly("password");
    }

    @Test
    @SuppressWarnings("unchecked")
    void upsert_omitting_secret_keeps_existing_value() {
        IT.exchange(port, "/admin/api/tenants/" + tenantId + "/settings/MQTT",
                HttpMethod.PUT, adminToken,
                Map.of("values", Map.of(
                        "host", "h", "port", 1883, "transport", "tls", "password", "first")),
                Map.class);

        IT.exchange(port, "/admin/api/tenants/" + tenantId + "/settings/MQTT",
                HttpMethod.PUT, adminToken,
                Map.of("values", Map.of("host", "h.v2", "port", 1883, "transport", "tls")),
                Map.class);

        var stored = settings.findByTenantIdAndKind(tenantId, SettingsKind.MQTT).orElseThrow();
        assertThat(stored.values()).containsEntry("password", "first");
        assertThat(stored.values()).containsEntry("host", "h.v2");
    }

    @Test
    @SuppressWarnings("unchecked")
    void invalid_values_return_422() {
        var resp = IT.exchange(port, "/admin/api/tenants/" + tenantId + "/settings/MQTT",
                HttpMethod.PUT, adminToken,
                Map.of("values", Map.of("host", "h", "port", 70000, "transport", "tls")),
                Map.class);
        assertThat(resp.getStatusCode()).isEqualTo(HttpStatus.UNPROCESSABLE_ENTITY);
    }

    @Test
    @SuppressWarnings("unchecked")
    void test_against_unreachable_host_returns_ok_false() {
        var resp = IT.exchange(port,
                "/admin/api/tenants/" + tenantId + "/settings/MQTT/test",
                HttpMethod.POST, adminToken,
                Map.of("values", Map.of(
                        // Reserved-for-doc TEST-NET-1 IP — never routable.
                        "host", "192.0.2.1", "port", 1883, "transport", "tls")),
                Map.class);
        assertThat(resp.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(resp.getBody()).containsEntry("ok", false);
        assertThat(resp.getBody().get("error")).isNotNull();
    }

    @Test
    @SuppressWarnings("unchecked")
    void list_returns_all_configured_kinds_for_the_tenant() {
        IT.exchange(port, "/admin/api/tenants/" + tenantId + "/settings/MQTT",
                HttpMethod.PUT, adminToken,
                Map.of("values", Map.of("host", "h", "port", 1883, "transport", "tls")),
                Map.class);
        IT.exchange(port, "/admin/api/tenants/" + tenantId + "/settings/DJI",
                HttpMethod.PUT, adminToken,
                Map.of("values", Map.of(
                        "region", "ap", "endpointUrl", "https://api-cloud.dji.com",
                        "appKey", "k", "appSecret", "s")),
                Map.class);

        var resp = IT.exchange(port, "/admin/api/tenants/" + tenantId + "/settings",
                HttpMethod.GET, adminToken, null, Map.class);
        assertThat(resp.getStatusCode()).isEqualTo(HttpStatus.OK);
        var items = (List<Map<String, Object>>) resp.getBody().get("items");
        assertThat(items).hasSize(2);
        assertThat(items).extracting(m -> m.get("kind")).containsExactlyInAnyOrder("MQTT", "DJI");
    }

    @Test
    @SuppressWarnings("unchecked")
    void delete_removes_the_row() {
        IT.exchange(port, "/admin/api/tenants/" + tenantId + "/settings/MQTT",
                HttpMethod.PUT, adminToken,
                Map.of("values", Map.of("host", "h", "port", 1883, "transport", "tls")),
                Map.class);

        var del = IT.exchange(port, "/admin/api/tenants/" + tenantId + "/settings/MQTT",
                HttpMethod.DELETE, adminToken, null, Void.class);
        assertThat(del.getStatusCode()).isEqualTo(HttpStatus.NO_CONTENT);
        assertThat(settings.findByTenantIdAndKind(tenantId, SettingsKind.MQTT)).isEmpty();
    }

    @Test
    @SuppressWarnings("unchecked")
    void unknown_tenant_returns_404() {
        var resp = IT.exchange(port, "/admin/api/tenants/ghost/settings/MQTT",
                HttpMethod.GET, adminToken, null, Map.class);
        assertThat(resp.getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
    }

    @Test
    @SuppressWarnings("unchecked")
    void archiving_tenant_clears_its_settings() {
        IT.exchange(port, "/admin/api/tenants/" + tenantId + "/settings/MQTT",
                HttpMethod.PUT, adminToken,
                Map.of("values", Map.of("host", "h", "port", 1883, "transport", "tls")),
                Map.class);
        assertThat(settings.findByTenantIdAndKind(tenantId, SettingsKind.MQTT)).isPresent();

        var del = IT.exchange(port, "/admin/api/tenants/" + tenantId,
                HttpMethod.DELETE, adminToken, null, Void.class);
        assertThat(del.getStatusCode()).isEqualTo(HttpStatus.NO_CONTENT);
        assertThat(settings.findByTenantIdAndKind(tenantId, SettingsKind.MQTT)).isEmpty();
    }
}
