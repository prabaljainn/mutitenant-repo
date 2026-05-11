package com.orochiverse.platform.testsupport;

import java.net.ConnectException;
import java.util.concurrent.TimeUnit;

import org.bson.Document;
import org.springframework.test.context.DynamicPropertyRegistry;

import com.mongodb.MongoClientSettings;
import com.mongodb.MongoSocketOpenException;
import com.mongodb.MongoTimeoutException;
import com.mongodb.client.MongoClients;

/**
 * Single source of truth for the integration-test Mongo connection: the
 * canonical URI, the {@link #mongoIsReachable} probe used in
 * {@code @EnabledIf}, and the {@link DynamicPropertyRegistry} hook ITs
 * call from their {@code @DynamicPropertySource} method.
 *
 * <p>Before this class existed, every IT inlined its own copy of the
 * 20-line probe; tweaks (e.g. extending the selection timeout) had to be
 * made in five places. Centralizing here also means tests outside
 * {@code iam.admin} no longer have to import {@code AdminItSupport} just
 * for the probe.
 */
public final class MongoTestSupport {

    /** Single-node replica-set dev URI, matching {@code docker-compose.yml}. */
    public static final String CONNECTION_URI =
            "mongodb://localhost:27017/iam_db?replicaSet=rs0&directConnection=true";

    private MongoTestSupport() {}

    /**
     * 2-second probe — long enough to ride out a slow Docker start, short
     * enough that CI without Mongo doesn't sit on it. Returns {@code false}
     * (rather than throwing) so it can be referenced directly from JUnit's
     * {@code @EnabledIf}, which gates the whole class on the boolean.
     */
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

    /**
     * Call from a test's {@code @DynamicPropertySource} method to wire the
     * Mongo URI into the Spring {@code Environment}. Saves the boilerplate
     * of importing {@link DynamicPropertyRegistry} just to register one
     * key.
     */
    public static void mongoProps(DynamicPropertyRegistry registry) {
        registry.add("spring.data.mongodb.uri", () -> CONNECTION_URI);
    }
}
