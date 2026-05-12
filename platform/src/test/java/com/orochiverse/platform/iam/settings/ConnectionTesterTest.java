package com.orochiverse.platform.iam.settings;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.net.ServerSocket;

import org.junit.jupiter.api.Test;

import com.sun.net.httpserver.HttpServer;

import com.orochiverse.platform.iam.settings.SettingsKindHandler.TestResult;

/**
 * Direct exercise of {@link ConnectionTester} — both the SSRF guard
 * (default-deny private addresses) and the happy paths against locally
 * spun-up servers.
 *
 * <p>For the success-path tests we set {@code allowPrivateTargets=true}
 * so the tester accepts loopback. The default-deny behaviour is tested
 * separately with the same construct.
 */
class ConnectionTesterTest {

    // ─────────────────────────────────────────────────────────────────────
    // Success paths — explicitly bypass the SSRF guard
    // ─────────────────────────────────────────────────────────────────────

    @Test
    void tcpProbe_succeeds_against_an_actually_listening_socket() throws IOException {
        var tester = new ConnectionTester(true);
        try (ServerSocket server = new ServerSocket(0, 1, java.net.InetAddress.getLoopbackAddress())) {
            int port = server.getLocalPort();
            TestResult r = tester.tcpProbe("127.0.0.1", port);
            assertThat(r.ok()).isTrue();
            assertThat(r.latencyMs()).isLessThan(1000);
        }
    }

    @Test
    void tcpProbe_reports_failure_against_an_unbound_port() {
        var tester = new ConnectionTester(true);
        // High random port we're confident nothing is listening on.
        TestResult r = tester.tcpProbe("127.0.0.1", 1);
        assertThat(r.ok()).isFalse();
        assertThat(r.error()).isNotBlank();
    }

    @Test
    void httpProbe_succeeds_against_a_local_http_server() throws IOException {
        var tester = new ConnectionTester(true);
        HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/", exchange -> {
            byte[] body = "ok".getBytes();
            exchange.sendResponseHeaders(200, body.length);
            try (OutputStream os = exchange.getResponseBody()) {
                os.write(body);
            }
        });
        server.start();
        try {
            String url = "http://127.0.0.1:" + server.getAddress().getPort() + "/";
            TestResult r = tester.httpProbe(url);
            assertThat(r.ok()).isTrue();
            assertThat(r.latencyMs()).isGreaterThanOrEqualTo(0).isLessThan(2000);
        } finally {
            server.stop(0);
        }
    }

    @Test
    void httpProbe_treats_4xx_as_reachable_not_an_error() throws IOException {
        // DJI's root actually returns 404. The handler should treat that
        // as "yes, this endpoint exists" — DNS resolved, TCP succeeded,
        // TLS handshake (if any) succeeded, HTTP server answered.
        var tester = new ConnectionTester(true);
        HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/", exchange -> {
            exchange.sendResponseHeaders(404, -1);
            exchange.close();
        });
        server.start();
        try {
            String url = "http://127.0.0.1:" + server.getAddress().getPort() + "/";
            TestResult r = tester.httpProbe(url);
            assertThat(r.ok()).isTrue();
        } finally {
            server.stop(0);
        }
    }

    @Test
    void httpProbe_treats_5xx_as_failure() throws IOException {
        var tester = new ConnectionTester(true);
        HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/", exchange -> {
            exchange.sendResponseHeaders(503, -1);
            exchange.close();
        });
        server.start();
        try {
            String url = "http://127.0.0.1:" + server.getAddress().getPort() + "/";
            TestResult r = tester.httpProbe(url);
            assertThat(r.ok()).isFalse();
            assertThat(r.error()).contains("503");
        } finally {
            server.stop(0);
        }
    }

    // ─────────────────────────────────────────────────────────────────────
    // SSRF guard — production behaviour (allow=false)
    // ─────────────────────────────────────────────────────────────────────

    @Test
    void tcpProbe_refuses_loopback_when_guard_is_on() {
        var tester = new ConnectionTester(false);
        TestResult r = tester.tcpProbe("127.0.0.1", 80);
        assertThat(r.ok()).isFalse();
        assertThat(r.error()).contains("loopback");
    }

    @Test
    void httpProbe_refuses_loopback_when_guard_is_on() {
        var tester = new ConnectionTester(false);
        TestResult r = tester.httpProbe("http://127.0.0.1/whatever");
        assertThat(r.ok()).isFalse();
        assertThat(r.error()).contains("loopback");
    }

    @Test
    void tcpProbe_refuses_aws_metadata_address() {
        // 169.254.169.254 — the classic cloud-metadata exfil target.
        // Java's isLinkLocalAddress covers 169.254.0.0/16.
        var tester = new ConnectionTester(false);
        TestResult r = tester.tcpProbe("169.254.169.254", 80);
        assertThat(r.ok()).isFalse();
        assertThat(r.error()).contains("link-local");
    }

    @Test
    void tcpProbe_refuses_rfc1918_addresses() {
        var tester = new ConnectionTester(false);
        for (String ip : new String[] { "10.0.0.1", "172.16.0.1", "192.168.0.1" }) {
            TestResult r = tester.tcpProbe(ip, 80);
            assertThat(r.ok()).as("refusing %s", ip).isFalse();
            assertThat(r.error()).contains("RFC1918");
        }
    }

    @Test
    void httpProbe_refuses_url_with_no_host() {
        var tester = new ConnectionTester(false);
        TestResult r = tester.httpProbe("not-a-url");
        assertThat(r.ok()).isFalse();
        assertThat(r.error()).containsAnyOf("no host part", "invalid url");
    }

    @Test
    void httpProbe_allows_routable_public_address_through_the_guard() {
        // 192.0.2.1 is RFC 5737 TEST-NET-1: documentation-only, not
        // RFC1918, not link-local. The guard accepts it (it's not in
        // any private range); the actual connection then fails because
        // nothing routes to TEST-NET-1.
        var tester = new ConnectionTester(false);
        TestResult r = tester.httpProbe("http://192.0.2.1/");
        assertThat(r.ok()).isFalse();
        // Specifically NOT "loopback" / "RFC1918" / "link-local" — it
        // got past the guard and failed at the network layer.
        assertThat(r.error()).doesNotContain("loopback");
        assertThat(r.error()).doesNotContain("RFC1918");
    }

    @Test
    void empty_inputs_fail_fast() {
        var tester = new ConnectionTester(false);
        assertThat(tester.tcpProbe("", 80).ok()).isFalse();
        assertThat(tester.tcpProbe(null, 80).ok()).isFalse();
        assertThat(tester.httpProbe("").ok()).isFalse();
        assertThat(tester.httpProbe(null).ok()).isFalse();
    }
}
