package com.orochiverse.platform.common.security.auth;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.Map;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import com.orochiverse.platform.common.security.jwt.AccessTokenIssuer;
import com.orochiverse.platform.common.security.principals.OperatorRole;
import com.orochiverse.platform.common.security.principals.TenantRole;
import com.orochiverse.platform.common.security.principals.UserKind;
import com.orochiverse.platform.common.tenant.TenantContext;

/**
 * End-to-end exercise of the Phase 1.6 auth filter: tokens issued by
 * {@link AccessTokenIssuer} flow through the real {@code SecurityFilterChain}
 * and reach {@code @PreAuthorize}-protected handlers and tenant-context-
 * dependent code.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@ActiveProfiles("test")
@Import(AuthFlowIT.TestEndpoints.class)
class AuthFlowIT {

    @LocalServerPort int port;
    @Autowired AccessTokenIssuer issuer;

    // ─────────────────────────────────────────────────────────────────────
    // /api/auth/me
    // ─────────────────────────────────────────────────────────────────────

    @Test
    void me_returns_401_without_a_bearer_token() {
        var response = new TestRestTemplate().getForEntity(url("/api/auth/me"), Map.class);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.UNAUTHORIZED);
        assertThat(response.getBody()).containsEntry("error", "unauthorized");
    }

    @Test
    void me_returns_401_with_a_garbage_bearer_token() {
        var response = exchange("/api/auth/me", "rotten.token", Map.class);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.UNAUTHORIZED);
    }

    @Test
    @SuppressWarnings("unchecked")
    void me_returns_the_operator_principal_for_a_valid_operator_token() {
        var token = issuer.issue("op-1", "op@orochi.example", UserKind.OPERATOR,
                OperatorRole.OPERATOR_ADMIN, null, null, 0).token();

        var response = exchange("/api/auth/me", token, Map.class);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        Map<String, Object> body = response.getBody();
        assertThat(body).containsEntry("userId", "op-1");
        assertThat(body).containsEntry("email", "op@orochi.example");
        assertThat(body).containsEntry("kind", "OPERATOR");
        assertThat(body).containsEntry("operatorRole", "OPERATOR_ADMIN");
        assertThat(body).containsEntry("activeTenantId", null);
        assertThat(body).containsEntry("tenantContextBound", false);
    }

    @Test
    @SuppressWarnings("unchecked")
    void me_returns_the_tenant_user_principal_and_binds_tenant_context() {
        var token = issuer.issue("tu-1", "alice@acme.example", UserKind.TENANT_USER,
                null, "acme", TenantRole.ADMIN, 0).token();

        var response = exchange("/api/auth/me", token, Map.class);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        Map<String, Object> body = response.getBody();
        assertThat(body).containsEntry("kind", "TENANT_USER");
        assertThat(body).containsEntry("activeTenantId", "acme");
        assertThat(body).containsEntry("tenantRole", "ADMIN");
        assertThat(body).containsEntry("tenantContextBound", true);
    }

    // ─────────────────────────────────────────────────────────────────────
    // Tenant context propagation into a downstream controller
    // ─────────────────────────────────────────────────────────────────────

    @Test
    @SuppressWarnings("unchecked")
    void downstream_handler_can_read_tenant_id_via_TenantContext() {
        var token = issuer.issue("tu-1", "alice@acme.example", UserKind.TENANT_USER,
                null, "acme", TenantRole.ADMIN, 0).token();

        var response = exchange("/test/whoami", token, Map.class);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(response.getBody()).containsEntry("tenantId", "acme");
    }

    @Test
    @SuppressWarnings("unchecked")
    void downstream_handler_sees_no_tenant_for_pure_operator_request() {
        var token = issuer.issue("op-1", "op@orochi.example", UserKind.OPERATOR,
                OperatorRole.OPERATOR_ADMIN, null, null, 0).token();

        var response = exchange("/test/whoami", token, Map.class);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(response.getBody()).containsEntry("tenantId", null);
    }

    // ─────────────────────────────────────────────────────────────────────
    // @PreAuthorize role mapping
    // ─────────────────────────────────────────────────────────────────────

    @Test
    void operator_admin_only_endpoint_returns_200_for_operator_admin() {
        var token = issuer.issue("op-1", "op@orochi.example", UserKind.OPERATOR,
                OperatorRole.OPERATOR_ADMIN, null, null, 0).token();

        var response = exchange("/test/admin-only", token, String.class);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(response.getBody()).isEqualTo("ok");
    }

    @Test
    @SuppressWarnings("unchecked")
    void operator_admin_only_endpoint_returns_403_for_operator_support() {
        var token = issuer.issue("op-2", "op2@orochi.example", UserKind.OPERATOR,
                OperatorRole.OPERATOR_SUPPORT, null, null, 0).token();

        var response = exchange("/test/admin-only", token, Map.class);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.FORBIDDEN);
        assertThat(response.getBody()).containsEntry("error", "forbidden");
    }

    @Test
    void operator_admin_only_endpoint_returns_403_for_tenant_user() {
        var token = issuer.issue("tu-1", "alice@acme.example", UserKind.TENANT_USER,
                null, "acme", TenantRole.ADMIN, 0).token();

        var response = exchange("/test/admin-only", token, Map.class);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.FORBIDDEN);
    }

    // ─────────────────────────────────────────────────────────────────────
    // Public endpoints stay open
    // ─────────────────────────────────────────────────────────────────────

    @Test
    void jwks_endpoint_remains_public() {
        var response = new TestRestTemplate().getForEntity(url("/.well-known/jwks.json"), Map.class);
        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
    }

    @Test
    void actuator_health_remains_public() {
        var response = new TestRestTemplate().getForEntity(url("/actuator/health"), String.class);
        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
    }

    // ─────────────────────────────────────────────────────────────────────
    // Helpers + test fixtures
    // ─────────────────────────────────────────────────────────────────────

    private String url(String path) {
        return "http://localhost:" + port + path;
    }

    private <T> org.springframework.http.ResponseEntity<T> exchange(String path, String token, Class<T> body) {
        var headers = new HttpHeaders();
        headers.setBearerAuth(token);
        return new TestRestTemplate().exchange(
                url(path), HttpMethod.GET, new HttpEntity<>(headers), body);
    }

    /**
     * Minimal controllers that exist only inside this IT to exercise the
     * security plumbing. Registered via {@code @Import} so they don't leak
     * into other tests or the production application.
     */
    @TestConfiguration
    static class TestEndpoints {

        @Bean
        WhoamiController whoami() {
            return new WhoamiController();
        }

        @Bean
        AdminOnlyController adminOnly() {
            return new AdminOnlyController();
        }
    }

    @RestController
    @RequestMapping("/test")
    static class WhoamiController {
        @GetMapping("/whoami")
        Map<String, Object> whoami() {
            // Reads through TenantContext — proves the filter bound it.
            // HashMap (not Map.of) because the value may be null.
            var body = new java.util.HashMap<String, Object>();
            body.put("tenantId", TenantContext.current().orElse(null));
            return body;
        }
    }

    @RestController
    @RequestMapping("/test")
    static class AdminOnlyController {
        @PreAuthorize("hasRole('OPERATOR_ADMIN')")
        @GetMapping("/admin-only")
        String adminOnly() {
            return "ok";
        }
    }
}
