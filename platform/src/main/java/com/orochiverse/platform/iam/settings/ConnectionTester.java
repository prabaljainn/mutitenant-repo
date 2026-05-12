package com.orochiverse.platform.iam.settings;

import java.io.IOException;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.net.URI;
import java.net.URISyntaxException;
import java.net.UnknownHostException;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import com.orochiverse.platform.iam.settings.SettingsKindHandler.TestResult;

/**
 * Smoke-test helpers shared by the {@link SettingsKindHandler}s. Kept in
 * a small Spring component so tests can swap in a stub for the network
 * calls.
 *
 * <h2>SSRF guard</h2>
 * The /test endpoint takes a user-supplied address and opens a TCP
 * socket or HTTP GET against it. That's a textbook SSRF primitive — an
 * operator (or a compromised operator account) could probe internal
 * IPs (cloud metadata at {@code 169.254.169.254}, RFC1918 ranges,
 * loopback) for reconnaissance or exploit pivoting. We resolve the
 * host first and refuse anything that isn't a routable public address.
 *
 * <p>The block list is enforced unconditionally in production. For
 * dev/test runs against MailHog/Mongo on localhost we accept loopback
 * <em>only</em> when the {@code platform.settings.allow-private-test-targets}
 * property is true — set in {@code application-dev.yml} so the live
 * dev stack stays usable, and never on in {@code application-prod.yml}.
 *
 * <h2>Timeout</h2>
 * Bounded by 3 seconds — the admin UI shows a spinner while this runs,
 * and "test takes forever" is worse UX than "test fails quickly with a
 * clear message".
 */
@Component
public class ConnectionTester {

    private static final Duration TIMEOUT = Duration.ofSeconds(3);

    private final boolean allowPrivateTargets;

    public ConnectionTester(
            @Value("${platform.settings.allow-private-test-targets:false}") boolean allowPrivateTargets) {
        this.allowPrivateTargets = allowPrivateTargets;
    }

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
        String denyReason = denyIfPrivate(host);
        if (denyReason != null) {
            return TestResult.fail(denyReason, 0);
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
        // Parse + resolve the host before opening the connection. If
        // we relied on HttpClient to do the resolving we'd lose the
        // ability to refuse private addresses up front.
        String host;
        try {
            URI uri = new URI(url);
            host = uri.getHost();
            if (host == null || host.isBlank()) {
                return TestResult.fail("endpoint url has no host part", 0);
            }
        } catch (URISyntaxException e) {
            return TestResult.fail("invalid url: " + e.getMessage(), 0);
        }
        String denyReason = denyIfPrivate(host);
        if (denyReason != null) {
            return TestResult.fail(denyReason, 0);
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

    /**
     * Resolves the host and returns a human-readable reason string
     * if the result falls in any blocked address class. Returns
     * {@code null} on accept.
     *
     * <p>Resolves both IPv4 and IPv6 — if EITHER resolution lands on
     * a private address we refuse (the DNS record might list multiple
     * A records and an attacker only needs one to point inward).
     */
    private String denyIfPrivate(String host) {
        if (allowPrivateTargets) {
            return null;
        }
        InetAddress[] addresses;
        try {
            addresses = InetAddress.getAllByName(host);
        } catch (UnknownHostException e) {
            // Fail open here — let the actual connection error surface
            // naturally, since "host doesn't resolve" is the user's
            // problem to fix, not a security issue.
            return null;
        }
        for (InetAddress addr : addresses) {
            String reason = describeIfPrivate(addr);
            if (reason != null) {
                return "refusing to probe " + host + " — resolves to " + addr.getHostAddress() + " (" + reason + ")";
            }
        }
        return null;
    }

    private static String describeIfPrivate(InetAddress addr) {
        if (addr.isLoopbackAddress())   return "loopback";
        if (addr.isLinkLocalAddress())  return "link-local (cloud metadata range)";
        if (addr.isSiteLocalAddress())  return "RFC1918 private";
        if (addr.isAnyLocalAddress())   return "wildcard";
        if (addr.isMulticastAddress())  return "multicast";
        // Java's isSiteLocalAddress covers 10/8, 172.16/12, 192.168/16
        // but NOT 100.64/10 (carrier-grade NAT) or fc00::/7 (unique
        // local IPv6). Explicit checks for both:
        byte[] bytes = addr.getAddress();
        if (bytes.length == 4) {
            int b0 = bytes[0] & 0xff;
            int b1 = bytes[1] & 0xff;
            if (b0 == 100 && b1 >= 64 && b1 <= 127) return "carrier-grade NAT";
        } else if (bytes.length == 16) {
            int b0 = bytes[0] & 0xff;
            if ((b0 & 0xfe) == 0xfc) return "IPv6 unique-local (fc00::/7)";
        }
        return null;
    }
}
