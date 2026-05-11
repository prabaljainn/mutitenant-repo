package com.orochiverse.platform.iam.auth;

import static org.assertj.core.api.Assertions.assertThat;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

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

import com.orochiverse.platform.common.security.jwt.AccessTokenIssuer;
import com.orochiverse.platform.common.security.passwords.PasswordHashing;
import com.orochiverse.platform.iam.users.UserRepository;
import com.orochiverse.platform.testsupport.IT;
import com.orochiverse.platform.testsupport.IamFixtures;
import com.orochiverse.platform.testsupport.JwtTestSupport;
import com.orochiverse.platform.testsupport.MongoTestSupport;

/**
 * End-to-end exercise of Phase 1.9 against the live MailHog SMTP capture:
 *
 * <ol>
 *   <li>Operator-admin invites a new operator → SMTP delivery → MailHog
 *       captures the email.</li>
 *   <li>Test fetches the email via MailHog's REST API
 *       (http://localhost:8025/api/v2/messages), parses the accept token
 *       out of the body.</li>
 *   <li>POST /api/auth/accept-invite with that token → user activated +
 *       logged in via the returned access token.</li>
 *   <li>Same dance for forgot-password → reset-password.</li>
 * </ol>
 *
 * <p>Skips itself if MailHog isn't reachable on :8025 — same gate pattern
 * as the Mongo-backed ITs.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@ActiveProfiles("integration")
@EnabledIf("com.orochiverse.platform.iam.auth.EmailFlowsIT#mailhogIsReachable")
class EmailFlowsIT {

    private static final String MAILHOG_API = "http://localhost:8025/api/v2";
    private static final HttpClient HTTP = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(2)).build();
    private static final Pattern TOKEN_PATTERN = Pattern.compile("token=([A-Za-z0-9_-]+)");

    static boolean mailhogIsReachable() {
        try {
            var req = HttpRequest.newBuilder(URI.create(MAILHOG_API + "/messages?limit=1"))
                    .timeout(Duration.ofSeconds(2)).GET().build();
            return HTTP.send(req, HttpResponse.BodyHandlers.ofString()).statusCode() == 200;
        } catch (Exception e) {
            return false;
        }
    }

    @DynamicPropertySource
    static void mongoProps(DynamicPropertyRegistry r) {
        MongoTestSupport.mongoProps(r);
    }

    @LocalServerPort int port;
    @Autowired UserRepository users;
    @Autowired PasswordHashing passwords;
    @Autowired AccessTokenIssuer issuer;

    private String suffix;
    private String adminEmail;
    private String adminId;
    private String adminToken;
    private String invitedOperatorEmail;
    private String invitedOperatorId;

    @BeforeEach
    void setUp() throws Exception {
        suffix = IamFixtures.randomSuffix();
        var admin = IamFixtures.operator(suffix)
                .id("admin-mail-" + suffix)
                .email("admin-mail-" + suffix + "@orochi.example")
                .password("S3cret!")
                .save(users, passwords);
        adminId = admin.id();
        adminEmail = admin.email();
        adminToken = JwtTestSupport.token(issuer, admin);
        clearMailhog();
    }

    @AfterEach
    void cleanup() throws Exception {
        users.deleteById(adminId);
        if (invitedOperatorId != null) {
            users.deleteById(invitedOperatorId);
        }
        clearMailhog();
    }

    // ─────────────────────────────────────────────────────────────────────
    // Invite → accept-invite flow
    // ─────────────────────────────────────────────────────────────────────

    @Test
    @SuppressWarnings("unchecked")
    void invite_email_arrives_and_accept_invite_logs_user_in() throws Exception {
        invitedOperatorEmail = "newop-" + suffix + "@orochi.example";

        // 1. Invite — service creates user + issues token + sends email.
        var inviteResp = IT.exchange(port, "/admin/api/operators",
                HttpMethod.POST, adminToken,
                Map.of("email", invitedOperatorEmail,
                        "firstName", "New", "lastName", "Operator", "role", "OPERATOR_SUPPORT"),
                Map.class);
        assertThat(inviteResp.getStatusCode()).isEqualTo(HttpStatus.CREATED);
        invitedOperatorId = (String) inviteResp.getBody().get("id");

        // 2. Pull the email from MailHog. SMTP send is synchronous (no
        // background queue) so it should be there immediately.
        String body = waitForEmailTo(invitedOperatorEmail, Duration.ofSeconds(5));
        assertThat(body)
                .contains("OPERATOR_SUPPORT")
                .contains("Hi New,")
                .contains("/accept-invite?token=");

        // 3. Extract the accept token.
        Matcher m = TOKEN_PATTERN.matcher(body);
        assertThat(m.find()).as("accept-invite link not found in body:\n" + body).isTrue();
        String token = m.group(1);

        // 4. Accept the invite — auto-login pair returned.
        var accept = new TestRestTemplate().postForEntity(IT.url(port, "/api/auth/accept-invite"),
                Map.of("token", token, "newPassword", "MyNewPass123!"), Map.class);
        assertThat(accept.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(accept.getBody()).containsKey("accessToken").containsKey("refreshToken");

        // 5. The user is now ACTIVE in iam_db.
        var stored = users.findById(invitedOperatorId).orElseThrow();
        assertThat(stored.passwordHash()).isNotNull();
        assertThat(stored.status().name()).isEqualTo("ACTIVE");

        // 6. Fresh login with the new password works.
        String accessToken = login(invitedOperatorEmail, "MyNewPass123!");
        assertThat(accessToken).isNotBlank();
    }

    @Test
    @SuppressWarnings("unchecked")
    void replaying_an_invite_token_is_rejected() throws Exception {
        invitedOperatorEmail = "replay-" + suffix + "@orochi.example";
        var inviteResp = IT.exchange(port, "/admin/api/operators",
                HttpMethod.POST, adminToken,
                Map.of("email", invitedOperatorEmail,
                        "firstName", "R", "lastName", "P", "role", "OPERATOR_SUPPORT"),
                Map.class);
        invitedOperatorId = (String) inviteResp.getBody().get("id");

        String token = TOKEN_PATTERN.matcher(waitForEmailTo(invitedOperatorEmail, Duration.ofSeconds(5)))
                .results().findFirst().orElseThrow().group(1);

        // First accept succeeds.
        var first = new TestRestTemplate().postForEntity(IT.url(port, "/api/auth/accept-invite"),
                Map.of("token", token, "newPassword", "First!"), Map.class);
        assertThat(first.getStatusCode()).isEqualTo(HttpStatus.OK);

        // Second accept with the same token must be rejected.
        var second = new TestRestTemplate().postForEntity(IT.url(port, "/api/auth/accept-invite"),
                Map.of("token", token, "newPassword", "Second!"), Map.class);
        assertThat(second.getStatusCode()).isEqualTo(HttpStatus.UNAUTHORIZED);
    }

    // ─────────────────────────────────────────────────────────────────────
    // Forgot password → reset-password flow
    // ─────────────────────────────────────────────────────────────────────

    @Test
    @SuppressWarnings("unchecked")
    void forgot_password_emails_a_link_and_reset_changes_the_password() throws Exception {
        // 1. Trigger reset for the seeded admin.
        var fp = new TestRestTemplate().postForEntity(IT.url(port, "/api/auth/forgot-password"),
                Map.of("email", adminEmail), Void.class);
        assertThat(fp.getStatusCode()).isEqualTo(HttpStatus.NO_CONTENT);

        // 2. Pull the email + extract the token.
        String body = waitForEmailTo(adminEmail, Duration.ofSeconds(5));
        assertThat(body)
                .contains("Hi Op,")  // seeded admin's firstName, see IamFixtures.OperatorBuilder default
                .contains("/reset-password?token=");
        String token = TOKEN_PATTERN.matcher(body).results().findFirst().orElseThrow().group(1);

        // 3. Reset to a new password.
        var reset = new TestRestTemplate().postForEntity(IT.url(port, "/api/auth/reset-password"),
                Map.of("token", token, "newPassword", "BrandNew99!"), Void.class);
        assertThat(reset.getStatusCode()).isEqualTo(HttpStatus.NO_CONTENT);

        // 4. Old password no longer works.
        var oldLogin = new TestRestTemplate().postForEntity(IT.url(port, "/api/auth/login"),
                Map.of("email", adminEmail, "password", "S3cret!"), Map.class);
        assertThat(oldLogin.getStatusCode()).isEqualTo(HttpStatus.UNAUTHORIZED);

        // 5. New password works.
        var newLogin = new TestRestTemplate().postForEntity(IT.url(port, "/api/auth/login"),
                Map.of("email", adminEmail, "password", "BrandNew99!"), Map.class);
        assertThat(newLogin.getStatusCode()).isEqualTo(HttpStatus.OK);
    }

    @Test
    void forgot_password_for_unknown_email_is_silently_204() throws Exception {
        var resp = new TestRestTemplate().postForEntity(IT.url(port, "/api/auth/forgot-password"),
                Map.of("email", "ghost-" + suffix + "@nowhere.example"), Void.class);

        assertThat(resp.getStatusCode()).isEqualTo(HttpStatus.NO_CONTENT);

        // No email should have been sent. Give MailHog a beat in case there's
        // any delay, then confirm zero messages.
        Thread.sleep(200);
        assertThat(messageCount()).isZero();
    }

    @Test
    @SuppressWarnings("unchecked")
    void reset_password_with_invalid_token_returns_401() {
        var resp = new TestRestTemplate().postForEntity(IT.url(port, "/api/auth/reset-password"),
                Map.of("token", "never-issued", "newPassword", "Whatever!"), Map.class);

        assertThat(resp.getStatusCode()).isEqualTo(HttpStatus.UNAUTHORIZED);
        assertThat(resp.getBody()).containsEntry("error", "invalid_token");
    }

    // ─────────────────────────────────────────────────────────────────────
    // MailHog REST API helpers
    // ─────────────────────────────────────────────────────────────────────

    // Goes through the real /login HTTP path on purpose — these tests check that
    // the password just set via accept-invite or reset-password actually authenticates.
    @SuppressWarnings("unchecked")
    private String login(String email, String password) {
        var resp = new TestRestTemplate().postForEntity(
                IT.url(port, "/api/auth/login"), Map.of("email", email, "password", password), Map.class);
        assertThat(resp.getStatusCode()).isEqualTo(HttpStatus.OK);
        return (String) resp.getBody().get("accessToken");
    }

    /**
     * Polls MailHog until a message addressed to {@code recipient} appears
     * (or {@code timeout} elapses). Returns the message body text.
     */
    private String waitForEmailTo(String recipient, Duration timeout) throws Exception {
        long deadline = System.currentTimeMillis() + timeout.toMillis();
        while (System.currentTimeMillis() < deadline) {
            String body = findMessageBodyTo(recipient);
            if (body != null) return body;
            Thread.sleep(100);
        }
        throw new AssertionError("no email arrived for " + recipient + " within " + timeout);
    }

    /**
     * Naive parser of MailHog's JSON. Only needs to find a message with
     * the right To address and return its body — no need for a full JSON
     * parser dep just for one IT.
     */
    private String findMessageBodyTo(String recipient) throws Exception {
        var req = HttpRequest.newBuilder(URI.create(MAILHOG_API + "/messages?limit=50"))
                .timeout(Duration.ofSeconds(2)).GET().build();
        String raw = HTTP.send(req, HttpResponse.BodyHandlers.ofString()).body();

        // Look for a "Mailbox":"<local-part>" + "Domain":"<domain>" pair
        // within a single message item, then back-track to the body.
        // MailHog wraps each item with a "Content":{"Body":"...","Headers":...}.
        int idx = 0;
        String[] parts = raw.split("\"ID\"");
        for (int i = 1; i < parts.length; i++) {
            String item = parts[i];
            String[] addr = recipient.split("@");
            if (item.contains("\"Mailbox\":\"" + addr[0] + "\"")
                    && item.contains("\"Domain\":\"" + addr[1] + "\"")) {
                // Body is "Body":"....." up to the next unescaped quote.
                int bodyStart = item.indexOf("\"Body\":\"");
                if (bodyStart < 0) continue;
                bodyStart += "\"Body\":\"".length();
                int bodyEnd = bodyStart;
                while (bodyEnd < item.length()) {
                    char c = item.charAt(bodyEnd);
                    if (c == '"' && item.charAt(bodyEnd - 1) != '\\') break;
                    bodyEnd++;
                }
                return item.substring(bodyStart, bodyEnd)
                        .replace("\\r\\n", "\n").replace("\\n", "\n").replace("\\\"", "\"");
            }
            idx = i;
        }
        return null;
    }

    private int messageCount() throws Exception {
        var req = HttpRequest.newBuilder(URI.create(MAILHOG_API + "/messages?limit=200"))
                .timeout(Duration.ofSeconds(2)).GET().build();
        String raw = HTTP.send(req, HttpResponse.BodyHandlers.ofString()).body();
        // MailHog returns {"total": <n>, "count": <n>, "items": [...]}.
        // Use the explicit total field rather than counting "ID" matches —
        // those appear inside addresses too and the empty-inbox case has zero.
        Matcher m = Pattern.compile("\"total\"\\s*:\\s*(\\d+)").matcher(raw);
        return m.find() ? Integer.parseInt(m.group(1)) : 0;
    }

    private void clearMailhog() throws Exception {
        // MailHog's bulk-delete is on the v1 API only — v2 only adds GET filters.
        var req = HttpRequest.newBuilder(URI.create("http://localhost:8025/api/v1/messages"))
                .timeout(Duration.ofSeconds(2)).DELETE().build();
        HTTP.send(req, HttpResponse.BodyHandlers.discarding());
    }
}
