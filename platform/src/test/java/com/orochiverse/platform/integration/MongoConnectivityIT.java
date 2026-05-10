package com.orochiverse.platform.integration;

import static org.assertj.core.api.Assertions.assertThat;

import java.net.ConnectException;
import java.util.concurrent.TimeUnit;

import org.bson.Document;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIf;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.data.mongodb.core.MongoTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

import com.mongodb.MongoClientSettings;
import com.mongodb.MongoSocketOpenException;
import com.mongodb.MongoTimeoutException;
import com.mongodb.client.MongoClients;

/**
 * Phase 1.2 integration test — proves the Spring Data Mongo wiring works
 * against a real MongoDB 8 replica-set node.
 *
 * Why not Testcontainers? As of May 2026, the docker-java client bundled
 * with Testcontainers 1.21.x doesn't yet recognise Docker Desktop 29.x's
 * Engine API (1.53). Until that's fixed upstream, integration tests run
 * against the local dev stack started by {@code ./scripts/dev-up.sh}.
 *
 * The {@link #mongoIsReachable} guard means CI and dev machines that
 * don't have the stack up will SKIP the test (not fail it), keeping
 * {@code mvn verify} green while still exercising the contract whenever
 * Mongo is available.
 */
@SpringBootTest
@ActiveProfiles("integration")
@EnabledIf("com.orochiverse.platform.integration.MongoConnectivityIT#mongoIsReachable")
class MongoConnectivityIT {

    private static final String HOST = "localhost";
    private static final int PORT = 27017;
    private static final String DB_NAME = "iam_db_it_smoke";
    private static final String CONNECTION_URI =
            "mongodb://" + HOST + ":" + PORT + "/" + DB_NAME + "?replicaSet=rs0&directConnection=true";

    /** JUnit 5 condition: skip if no replica-set primary is reachable. */
    static boolean mongoIsReachable() {
        var settings = MongoClientSettings.builder()
                .applyConnectionString(new com.mongodb.ConnectionString(CONNECTION_URI))
                .applyToClusterSettings(c -> c.serverSelectionTimeout(2, TimeUnit.SECONDS))
                .build();
        try (var client = MongoClients.create(settings)) {
            client.getDatabase("admin").runCommand(new Document("ping", 1));
            return true;
        } catch (MongoTimeoutException | MongoSocketOpenException e) {
            System.err.println("[MongoConnectivityIT] Skipping — no Mongo at " + HOST + ":" + PORT
                    + ". Run ./scripts/dev-up.sh to enable this test.");
            return false;
        } catch (Exception e) {
            if (e.getCause() instanceof ConnectException) {
                System.err.println("[MongoConnectivityIT] Skipping — Mongo unreachable: " + e.getMessage());
                return false;
            }
            throw new RuntimeException("Unexpected error checking Mongo reachability", e);
        }
    }

    @DynamicPropertySource
    static void mongoProps(DynamicPropertyRegistry registry) {
        registry.add("spring.data.mongodb.uri", () -> CONNECTION_URI);
    }

    @Autowired
    MongoTemplate mongo;

    @BeforeAll
    static void announce() {
        System.out.println("[MongoConnectivityIT] Connecting to " + CONNECTION_URI);
    }

    @Test
    void mongo_template_is_wired_and_reachable() {
        assertThat(mongo).isNotNull();
        assertThat(mongo.getDb().getName()).isEqualTo(DB_NAME);
        assertThat(mongo.executeCommand("{ ping: 1 }").get("ok")).isEqualTo(1.0);
    }

    @Test
    void can_write_and_read_a_document() {
        var collection = "phase_1_2_smoke";
        mongo.dropCollection(collection);
        mongo.insert(new Document("hello", "orochiverse"), collection);
        var found = mongo.getCollection(collection).find().first();
        assertThat(found).isNotNull();
        assertThat(found.getString("hello")).isEqualTo("orochiverse");
        mongo.dropCollection(collection);
    }

    @Test
    void replica_set_is_initialised_and_writable() {
        var hello = mongo.executeCommand("{ hello: 1 }");
        assertThat(hello.getBoolean("isWritablePrimary")).isTrue();
        assertThat(hello.getString("setName")).isEqualTo("rs0");
    }
}
