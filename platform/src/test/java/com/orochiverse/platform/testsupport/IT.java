package com.orochiverse.platform.testsupport;

import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;

/**
 * Tiny HTTP helper for {@code @SpringBootTest(webEnvironment = RANDOM_PORT)}
 * integration tests. Bundles the three things that every IT used to inline:
 * URL building from a port, building a Bearer + JSON header, and a generic
 * {@code exchange}.
 *
 * <p>Stateless static methods so tests don't have to instantiate a helper —
 * they already have {@code int port} from {@code @LocalServerPort}.
 */
public final class IT {

    private IT() {}

    /** Absolute URL for {@code path} against the random local server port. */
    public static String url(int port, String path) {
        return "http://localhost:" + port + path;
    }

    /** {@code Authorization: Bearer …} + {@code Content-Type: application/json}. */
    public static HttpHeaders bearer(String token) {
        var h = new HttpHeaders();
        if (token != null) h.setBearerAuth(token);
        h.setContentType(MediaType.APPLICATION_JSON);
        return h;
    }

    /**
     * Generic authenticated exchange. Pass {@code null} for {@code body}
     * for verbs without a payload (GET/DELETE).
     */
    public static <T> ResponseEntity<T> exchange(int port, String path, HttpMethod method,
                                                 String token, Object body, Class<T> type) {
        var headers = bearer(token);
        return new TestRestTemplate().exchange(
                url(port, path), method, new HttpEntity<>(body, headers), type);
    }

    /** Anonymous GET — no auth header. */
    public static <T> ResponseEntity<T> getAnon(int port, String path, Class<T> type) {
        return new TestRestTemplate().getForEntity(url(port, path), type);
    }

    /** Anonymous POST — no auth header. */
    public static <T> ResponseEntity<T> postAnon(int port, String path, Object body, Class<T> type) {
        return new TestRestTemplate().postForEntity(url(port, path), body, type);
    }
}
