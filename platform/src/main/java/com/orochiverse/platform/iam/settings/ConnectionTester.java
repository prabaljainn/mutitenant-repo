package com.orochiverse.platform.iam.settings;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;

import org.springframework.stereotype.Component;

import com.orochiverse.platform.iam.settings.SettingsKindHandler.TestResult;

/**
 * Smoke-test helpers shared by the {@link SettingsKindHandler}s. Kept in
 * a small Spring component so tests can swap in a stub for the network
 * calls.
 *
 * <p>Bounded by a 3-second timeout — the admin UI shows a spinner while
 * this runs, and "test takes forever" is worse UX than "test fails
 * quickly with a clear message".
 */
@Component
public class ConnectionTester {

    private static final Duration TIMEOUT = Duration.ofSeconds(3);

    /**
     * Opens a TCP connection to host:port and immediately closes it.
     * For MQTT this is the "is the broker reachable" smoke test —
     * doing a full MQTT CONNECT would need the client library and
     * credentials to be valid, which is more than this UI needs to
     * answer.
     */
    public TestResult tcpProbe(String host, int port) {
        if (host == null || host.isBlank()) {
            return TestResult.fail("host is required", 0);
        }
        long start = System.currentTimeMillis();
        try (Socket s = new Socket()) {
            s.connect(new InetSocketAddress(host, port), (int) TIMEOUT.toMillis());
            return TestResult.ok(System.currentTimeMillis() - start);
        } catch (IOException e) {
            return TestResult.fail(e.getMessage(), System.currentTimeMillis() - start);
        }
    }

    /**
     * HTTPS GET against the given URL. Any 2xx/3xx/4xx is "the endpoint
     * exists and answered" — DJI's root returns 404 by design, so we
     * treat anything that came back over the wire as success. Only
     * IO / TLS failures count as not-reachable.
     */
    public TestResult httpProbe(String url) {
        if (url == null || url.isBlank()) {
            return TestResult.fail("endpoint url is required", 0);
        }
        long start = System.currentTimeMillis();
        try {
            var client = HttpClient.newBuilder().connectTimeout(TIMEOUT).build();
            var req = HttpRequest.newBuilder(URI.create(url))
                    .timeout(TIMEOUT).GET().build();
            HttpResponse<Void> resp = client.send(req, HttpResponse.BodyHandlers.discarding());
            long latency = System.currentTimeMillis() - start;
            if (resp.statusCode() >= 500) {
                return TestResult.fail("upstream returned " + resp.statusCode(), latency);
            }
            return TestResult.ok(latency);
        } catch (Exception e) {
            return TestResult.fail(e.getMessage(), System.currentTimeMillis() - start);
        }
    }
}
