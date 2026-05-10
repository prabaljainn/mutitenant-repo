package com.orochiverse.platform.smoke;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.test.context.ActiveProfiles;

/**
 * Phase 1.1 smoke test: the Spring context loads and the actuator health
 * endpoint responds. This is intentionally minimal — no security filters,
 * no Mongo, no Redis. Real integration coverage lands in later phases.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@ActiveProfiles("test")
class ApplicationBootSmokeTest {

    @LocalServerPort
    int port;

    @Test
    void context_loads() {
        // The @SpringBootTest annotation alone proves the context wires up.
        // Failure here means a misconfiguration in pom.xml or application.yml.
    }

    @Test
    void actuator_health_returns_up() {
        var rest = new TestRestTemplate();
        ResponseEntity<String> response = rest.getForEntity(
            "http://localhost:" + port + "/actuator/health", String.class);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(response.getBody()).contains("\"status\":\"UP\"");
    }
}
