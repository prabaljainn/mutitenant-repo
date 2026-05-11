package com.orochiverse.platform.iam.admin;

import java.net.ConnectException;
import java.util.UUID;
import java.util.concurrent.TimeUnit;

import org.bson.Document;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;

import com.mongodb.MongoClientSettings;
import com.mongodb.MongoSocketOpenException;
import com.mongodb.MongoTimeoutException;
import com.mongodb.client.MongoClients;

import com.orochiverse.platform.common.security.passwords.PasswordHashing;
import com.orochiverse.platform.common.security.principals.OperatorRole;
import com.orochiverse.platform.iam.users.User;
import com.orochiverse.platform.iam.users.UserRepository;
import com.orochiverse.platform.iam.users.UserStatus;

/**
 * Shared helpers for the {@code /admin/api/*} integration tests:
 * Mongo-reachability gate, request shortcuts, and a fixture that creates
 * an OPERATOR_ADMIN whose token can be obtained from {@code /api/auth/login}.
 *
 * <p>Kept package-private and dependency-free so it can live next to the
 * tests without forcing an extra autoconfig surface.
 */
public final class AdminItSupport {

    public static final String CONNECTION_URI =
            "mongodb://localhost:27017/iam_db?replicaSet=rs0&directConnection=true";

    private AdminItSupport() {}

    public static boolean mongoIsReachable() {
        var settings = MongoClientSettings.builder()
                .applyConnectionString(new com.mongodb.ConnectionString(CONNECTION_URI))
                .applyToClusterSettings(c -> c.serverSelectionTimeout(2, TimeUnit.SECONDS))
                .build();
        try (var client = MongoClients.create(settings)) {
            client.getDatabase("admin").runCommand(new Document("ping", 1));
            return true;
        } catch (MongoTimeoutException | MongoSocketOpenException e) {
            return false;
        } catch (Exception e) {
            if (e.getCause() instanceof ConnectException) {
                return false;
            }
            throw new RuntimeException(e);
        }
    }

    /** Random 8-char suffix for namespacing test data. */
    public static String randomSuffix() {
        return UUID.randomUUID().toString().substring(0, 8);
    }

    public static HttpHeaders bearer(String token) {
        var h = new HttpHeaders();
        h.setBearerAuth(token);
        h.setContentType(MediaType.APPLICATION_JSON);
        return h;
    }

    public static <T> ResponseEntity<T> exchange(String url, HttpMethod method, String token,
                                                 Object body, Class<T> type) {
        var headers = bearer(token);
        return new TestRestTemplate().exchange(url, method, new HttpEntity<>(body, headers), type);
    }

    /** Creates an ACTIVE operator with the given role. */
    public static String seedOperator(UserRepository users, PasswordHashing passwords,
                                      String suffix, String email, String password, OperatorRole role) {
        String id = "op-" + suffix;
        users.save(new User(id, email, passwords.hash(password),
                "Op", "Admin",
                UserStatus.ACTIVE,
                com.orochiverse.platform.common.security.principals.UserKind.OPERATOR,
                role,
                null, null, 0, null,
                java.time.Instant.now(), java.time.Instant.now()));
        return id;
    }
}
